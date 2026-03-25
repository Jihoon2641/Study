# Long Polling 채팅 서버

> Short Polling → **Long Polling** → WebSocket 으로 이어지는 실시간 통신 진화 과정을 학습하기 위한 프로젝트

---

## 실시간 통신 방식 비교

| 방식 | 동작 | 장점 | 단점 |
|---|---|---|---|
| **Short Polling** | 일정 주기(e.g. 1초)마다 클라이언트가 요청 | 구현 단순 | 빈 응답 낭비, 서버 부하 |
| **Long Polling** | 새 데이터 생길 때까지 응답 보류 | 빈 응답 없음, HTTP 그대로 사용 | 응답 후 재연결 필요 |
| **WebSocket** | 하나의 연결을 끝까지 유지 (양방향) | 가장 효율적 | 프로토콜 업그레이드 필요, 복잡 |

---

## Long Polling 핵심 개념

### HTTP 연결 유지

Long Polling은 일반 HTTP 요청과 동일하게 TCP 연결을 맺지만, **서버가 응답을 즉시 보내지 않고 데이터가 생길 때까지 연결을 열어둔다.**

```
클라이언트                          서버
    │── GET /api/messages/poll ────>│  ← TCP 연결 수립
    │                               │
    │   ← 응답 헤더 아직 안 옴      │  ← DeferredResult 대기 중
    │   ← 연결은 살아있음 (open)    │  ← (최대 30초 대기)
    │                               │
    │<── HTTP 200 + 메시지 데이터 ──│  ← 메시지 생기면 그때 응답
    │                               │
    │── GET /api/messages/poll ────>│  ← 즉시 재연결
```

---

## 프로젝트 구조

```
websocketV2/
├── controller/
│   └── ChatController.java       # REST API 엔드포인트
├── service/
│   └── ChatService.java          # Long Polling 핵심 로직
├── dto/
│   └── Chatmessage.java          # 메시지 DTO
├── actuator/
│   └── Longpollingstats.java     # 통계 수집
└── resources/static/
    └── index.html                # 클라이언트 (JS Long Polling 구현)
```

---

## 핵심 구현 상세

### 1. `DeferredResult` — 응답을 나중에 보내는 약속 객체

Spring MVC의 `DeferredResult<T>`는 **응답 시점을 코드가 직접 제어**할 수 있는 객체다.

```java
// 일반 Controller: return 즉시 응답 전송
@GetMapping("/sync")
public List<Chatmessage> getMessages() {
    return messages; // ← 여기서 바로 HTTP 응답
}

// DeferredResult: return해도 HTTP 응답은 아직 안 보냄
@GetMapping("/messages/poll")
public DeferredResult<List<Chatmessage>> longPoll(...) {
    DeferredResult<List<Chatmessage>> deferred = new DeferredResult<>(30000L);
    // ... deferred를 어딘가에 저장
    return deferred; // ← HTTP 연결 유지, 스레드는 반납

    // 나중에 다른 스레드에서:
    // deferred.setResult(data); ← 이 순간 HTTP 응답이 클라이언트에게 전송됨
}
```

**스레드 관점 장점:**
- 일반 방식: 요청 1개 = 스레드 1개가 30초 동안 점유
- DeferredResult: 요청을 처리한 스레드는 즉시 반납 → 다른 요청 처리 가능

---

### 2. `waitForMessages` — 서버 대기 로직

`ChatService.waitForMessages()`는 클라이언트의 요청마다 1번씩 실행된다.

```java
public DeferredResult<List<Chatmessage>> waitForMessages(long lastId, long timeoutMs) {
    DeferredResult<List<Chatmessage>> deferred = new DeferredResult<>(timeoutMs);

    // 1. 이미 새 메시지가 있으면 즉시 응답 (대기 불필요)
    List<Chatmessage> existing = getMessagesSince(lastId);
    if (!existing.isEmpty()) {
        deferred.setResult(existing);
        return deferred;
    }

    // 2. 새 메시지 없음 → 대기 목록에 등록하고 리턴 (연결 유지)
    PendingRequest pending = new PendingRequest(deferred, lastId);
    pendingRequests.add(pending);

    // 3. 30초 타임아웃 → 빈 리스트 응답 후 클라이언트가 재요청
    deferred.onTimeout(() -> {
        pendingRequests.remove(pending);
        deferred.setResult(List.of());
    });

    return deferred;
}
```

---

### 3. `sendMessage` → `notifyPendingClients` — 메시지 Push

메시지가 전송되면 대기 중인 **모든** 클라이언트에게 즉시 응답을 보낸다.

```java
public Chatmessage sendMessage(String sender, String content) {
    long id = idGenerator.incrementAndGet(); // 싱글톤이므로 앱 살아있는 동안 계속 증가
    Chatmessage message = Chatmessage.of(id, sender, content);
    messages.add(message);

    notifyPendingClients(id); // ← 대기 중인 클라이언트에게 Push
    return message;
}

private void notifyPendingClients(long newMessageId) {
    for (PendingRequest pending : pendingRequests) {
        List<Chatmessage> newMessages = getMessagesSince(pending.lastId());
        if (!newMessages.isEmpty() && !pending.deferred().isSetOrExpired()) {
            pending.deferred().setResult(newMessages); // ← HTTP 응답 전송
            pendingRequests.remove(pending);
        }
    }
}
```

---

### 4. 클라이언트 폴링 루프 (JavaScript)

**폴링은 서버가 아니라 클라이언트가 반복한다.**

```javascript
// Short Polling: 타이머로 주기적 요청 (응답 기다리지 않음)
setInterval(() => fetch('/api/messages/poll'), 1000);

// Long Polling: 응답 올 때까지 await으로 대기, 오면 즉시 재요청
async function startLongPolling() {
    while (true) {
        await longPoll(); // ← 응답 올 때까지 여기서 멈춤 (최대 30초)
    }
}

async function longPoll() {
    const res = await fetch(`/api/messages/poll?lastId=${lastId}&timeout=30000`);
    //          ↑ 서버가 응답할 때까지 HTTP 연결 유지하며 대기
    const messages = await res.json();
    // ... messages 처리 후 루프 반복
}
```

---

## 전체 실행 흐름 예시

```
클라이언트A          서버                    클라이언트B
    │                  │                         │
    │── poll(lastId=5)→│                         │
    │                  │ pendingRequests 등록      │
    │   (대기 중...)   │                         │
    │                  │←── POST "안녕" ─────────│
    │                  │ messages에 저장           │
    │                  │ notifyPendingClients()    │
    │                  │ deferred.setResult()      │
    │←── [메시지 id=6]─│                         │
    │                  │                         │
    │── poll(lastId=6)→│  ← 즉시 다음 폴링        │
```

---

## 메시지 ID 설계

`idGenerator`는 `AtomicLong`으로 **앱 실행 중 계속 증가**한다.

- `@Service`는 싱글톤 → `idGenerator` 인스턴스도 1개
- 클라이언트는 `lastId`를 기억해 **"내가 마지막으로 받은 id 이후" 메시지만 요청**
- 현재는 메모리 저장소(`CopyOnWriteArrayList`)이므로 앱 재시작 시 id와 메시지 모두 리셋 → 일관성 유지됨

```java
private List<Chatmessage> getMessagesSince(long lastId) {
    return messages.stream()
            .filter(msg -> msg.getId() > lastId) // id가 단조증가해야 올바르게 동작
            .toList();
}
```

---

## API

| Method | URL | 설명 |
|---|---|---|
| `GET` | `/api/messages/poll?lastId=0&timeout=30000` | Long Polling 엔드포인트 |
| `POST` | `/api/messages` | 메시지 전송 |
| `GET` | `/api/messages` | 전체 메시지 조회 |
| `GET` | `/api/stats` | Long Polling 통계 조회 |

---

## 실행

```bash
./gradlew bootRun
```

브라우저에서 `http://localhost:8080` 접속
