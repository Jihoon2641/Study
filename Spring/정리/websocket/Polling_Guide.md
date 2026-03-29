# 실시간 채팅 구현 발전 과정 — 1단계 & 2단계 상세 정리

## 목차

1. [배경 — 왜 이 두 단계가 필요한가](#1-배경)
2. [1단계 Short Polling](#2-1단계-short-polling)
   - 동작 흐름
   - 핵심 코드 분석
   - 주요 객체 설명
   - 문제점
3. [2단계 Long Polling](#3-2단계-long-polling)
   - 동작 흐름
   - 핵심 코드 분석
   - 주요 객체 설명
   - 문제점
4. [1단계 vs 2단계 비교](#4-1단계-vs-2단계-비교)
5. [공통 구조](#5-공통-구조)
6. [다음 단계 예고](#6-다음-단계-예고)

---

## 1. 배경

WebSocket 이전에 실시간처럼 보이는 채팅을 구현하려면 HTTP 프로토콜만으로 "서버에 새 메시지가 생겼을 때 클라이언트에게 알리는" 문제를 풀어야 했다.

HTTP의 근본적인 제약은 **클라이언트가 먼저 요청해야만 서버가 응답할 수 있다**는 점이다. 서버가 먼저 데이터를 밀어넣을 방법이 없다. 이 제약 안에서 실시간을 흉내 내기 위해 고안된 방식이 Polling이다.

```
[HTTP의 근본 제약]
Client ──── 요청 ────▶ Server  (클라이언트가 먼저)
Client ◀─── 응답 ──── Server

[원하는 것: 서버 Push]
Client ◀─── 새 메시지! ── Server  (서버가 먼저) → HTTP로는 불가
```

---

## 2. 1단계 Short Polling

### 개요

> "모르겠으니까 주기적으로 계속 물어보는" 방식

클라이언트가 일정 간격(예: 1초)마다 서버에 "새 메시지 있어요?" 라고 HTTP 요청을 반복하는 가장 단순한 방식이다.

---

### 동작 흐름

```
시간 →  0s      1s      2s      3s      4s      5s
        │       │       │       │       │       │
Client  ├─GET──▶├─GET──▶├─GET──▶├─GET──▶├─GET──▶│
        │◀─[]───│◀─[]───│◀─[]───│◀─[msg]│◀─[]───│
        │  없음  │  없음  │  없음  │  있음! │  없음  │
```

1. 클라이언트는 `setInterval`로 1초마다 `GET /api/messages/poll?lastId=N` 호출
2. 서버는 `lastId` 이후의 새 메시지를 조회
3. 새 메시지가 있으면 반환, **없으면 빈 배열을 즉시 반환**
4. 클라이언트는 응답을 받고 다음 타이머를 기다림
5. 1초 후 다시 1번으로 돌아감

**중요:** 서버는 응답을 절대 보류하지 않는다. 결과가 있든 없든 받는 즉시 응답한다.

---

### 핵심 코드 분석

#### 클라이언트 — 폴링 루프

```javascript
// 핵심: setInterval로 주기적 반복
function startPolling() {
    pollingTimer = setInterval(poll, pollingInterval); // 1000ms
}

async function poll() {
    const res = await fetch(`/api/messages/poll?lastId=${lastId}`);
    const data = await res.json();

    if (data.hasNew) {
        // 새 메시지가 있으면 렌더링
        data.messages.forEach(renderMessage);
        lastId = data.messages[data.messages.length - 1].id;
    }
    // 없으면 그냥 통과 → 1초 후 다시 요청
}
```

`setInterval`이 폴링의 본질이다. 메시지 유무와 완전히 무관하게 타이머가 작동한다.

#### 서버 컨트롤러 — 즉시 응답

```java
@GetMapping("/messages/poll")
public ResponseEntity<Map<String, Object>> poll(
        @RequestParam(defaultValue = "0") long lastId) {

    List<ChatMessage> messages = chatService.getMessagesSince(lastId);
    // 새 메시지 없으면 빈 리스트가 담긴 응답을 즉시 반환
    // → 기다리지 않는다. 이것이 Short Polling의 본질
    return ResponseEntity.ok(Map.of(
            "messages", messages,
            "hasNew",   !messages.isEmpty(),
            "stats",    /* 통계 */
    ));
}
```

반환 타입이 `ResponseEntity`인 것이 중요하다. `DeferredResult`가 아니므로 Spring은 이 메서드가 반환되는 즉시 HTTP 응답을 전송한다.

#### 서버 서비스 — 메시지 조회 즉시 반환

```java
public List<ChatMessage> getMessagesSince(long lastId) {
    List<ChatMessage> newMessages = messageStore.stream()
            .filter(msg -> msg.getId() > lastId)
            .toList();

    // 있든 없든 통계 기록 후 즉시 반환
    stats.recordRequest(!newMessages.isEmpty());
    return newMessages;  // 빈 리스트일 수 있음
}
```

---

### 주요 객체 설명

#### `ChatMessage`

```java
public class ChatMessage {
    private long   id;        // 전역 순차 ID. 클라이언트가 lastId로 활용
    private String sender;    // 발신자 닉네임
    private String content;   // 메시지 본문
    private String timestamp; // 서버 수신 시각 (HH:mm:ss.SSS)
}
```

`id` 필드가 Short Polling의 핵심이다. 클라이언트는 마지막으로 받은 메시지의 `id`를 기억해두었다가 다음 폴링 요청 시 `lastId` 파라미터로 전달한다. 서버는 이 값보다 큰 ID의 메시지만 필터링해서 반환한다. 중복 수신을 막는 유일한 장치다.

#### `PollingStats`

```java
public class PollingStats {
    private final AtomicLong totalRequests    = new AtomicLong(0); // 총 폴링 요청 수
    private final AtomicLong emptyResponses   = new AtomicLong(0); // 새 메시지 없이 응답한 횟수 (낭비)
    private final AtomicLong messagesDelivered = new AtomicLong(0); // 메시지를 담아 응답한 횟수
    private final AtomicLong totalMessages    = new AtomicLong(0); // 실제 전송된 메시지 수

    // 낭비율 = 빈 응답 수 / 전체 요청 수 * 100
    // Short Polling의 문제를 숫자로 표현하는 핵심 지표
    public double getWasteRatio() {
        long total = totalRequests.get();
        if (total == 0) return 0.0;
        return (double) emptyResponses.get() / total * 100;
    }
}
```

`AtomicLong`을 사용한 이유는 멀티스레드 환경에서 여러 HTTP 요청이 동시에 `increment`를 호출하기 때문이다. 일반 `long`이나 `int`를 사용하면 race condition으로 카운트가 누락될 수 있다.

`emptyResponses`와 `wasteRatio`가 이 단계의 핵심 관찰 지표다. 채팅이 조용할수록 이 값이 100%에 수렴한다.

#### `ChatService`

```java
@Service
public class ChatService {
    private final CopyOnWriteArrayList<ChatMessage> messageStore = new CopyOnWriteArrayList<>();
    private final AtomicLong idGenerator = new AtomicLong(0);
    private final PollingStats stats = new PollingStats();
}
```

`messageStore`를 `CopyOnWriteArrayList`로 선언한 이유가 있다. 여러 클라이언트가 동시에 `getMessagesSince()`를 호출하며 읽기 작업을 하는 동시에, 다른 클라이언트가 `sendMessage()`로 쓰기를 할 수 있다. 일반 `ArrayList`에 `synchronized` 없이 이렇게 하면 `ConcurrentModificationException`이 발생한다. `CopyOnWriteArrayList`는 쓰기 시 내부 배열을 복사하므로 읽기 작업이 안전하게 진행된다.

단, 쓰기 비용이 크므로 메시지가 매우 많이 쌓이는 프로덕션 환경에서는 별도 처리가 필요하다.

#### `ChatController`

```java
@RestController
@RequestMapping("/api")
public class ChatController {
    // GET  /api/messages/poll  — 폴링 엔드포인트 (핵심)
    // POST /api/messages       — 메시지 전송
    // GET  /api/messages       — 전체 메시지 조회 (초기 로드)
    // GET  /api/stats          — 통계 조회
}
```

Short Polling에서는 메시지 **수신**과 **송신**이 완전히 별개의 HTTP 요청이다. 수신은 `GET /poll`, 송신은 `POST /messages`. 이것이 이후 WebSocket에서 하나의 연결로 통합되는 것과 대비된다.

---

### 문제점

#### 1) 불필요한 HTTP 요청 폭발

```
사용자 100명, 폴링 간격 1초
→ 초당 100개 HTTP 요청 발생
→ 그 중 대화가 없으면 100개 전부 빈 응답
→ 서버 입장에서 완전한 낭비
```

1초마다 HTTP 연결 수립 → 요청 → 응답 → 연결 해제 사이클이 반복된다. 각 사이클마다 TCP 핸드셰이크, HTTP 헤더 파싱, 스레드 할당이 발생한다.

#### 2) 메시지 수신 지연 — 최대 폴링 간격만큼 지연

```
메시지 전송 시각: 00:00.500
다음 폴링 시각:   00:01.000
수신 시각:        00:01.000  ← 최대 0.5초 지연 (1초 간격 기준)
```

폴링 간격이 곧 최대 지연 시간이다. 1초 간격이면 최대 1초, 500ms 간격이면 최대 500ms 지연된다.

#### 3) 간격 딜레마 (트레이드오프)

| 폴링 간격 | 지연 시간 | 초당 요청 수 (100명 기준) |
|-----------|-----------|--------------------------|
| 3초       | 최대 3초  | 약 33개                  |
| 1초       | 최대 1초  | 100개                    |
| 500ms     | 최대 500ms | 200개                   |
| 100ms     | 최대 100ms | 1,000개                 |

지연을 줄이려면 간격을 짧게 해야 하고, 서버 부하를 줄이려면 간격을 길게 해야 한다. 이 둘을 동시에 만족할 수 없다.

#### 4) 확장성 한계

동시 사용자 N명이면 초당 N개의 HTTP 요청이 고정적으로 발생한다. 사용자가 늘어날수록 서버 부하가 선형으로 증가한다.

---

## 3. 2단계 Long Polling

### 개요

> "대답할 것이 생길 때까지 전화를 끊지 않는" 방식

Short Polling의 "빈 응답 낭비" 문제를 해결한다. 서버는 새 메시지가 없으면 응답을 즉시 보내지 않고 연결을 열어둔 채로 대기한다. 새 메시지가 생기면 그제서야 응답을 전송하고, 클라이언트는 받자마자 즉시 다음 요청을 보낸다.

---

### 동작 흐름

```
시간 →  0s                          30s    30.001s            60s
        │                            │      │                  │
Client  ├─GET /poll─────────────────▶│      ├─GET /poll───────▶│
        │  (서버가 연결 붙잡고 있음)  │      │                  │
        │                            │      │                  │
Server  │  [대기 중... 메시지 없음]   │      │  [대기 중...]     │
        │◀────────── [] ─────────────┤      │                  │
        │         (30초 타임아웃)     │      │                  │
        │                            │      │                  │

------- 메시지가 도착한 경우 -------

시간 →  0s          5s              
        │            │              
Client  ├─GET /poll─▶│              
        │  (대기 중)  │              
        │            │ ← 다른 클라이언트가 메시지 전송
        │◀──[msg]────┤  (5초 만에 즉시 응답)
        ├─GET /poll─▶│  ← 받자마자 즉시 재요청
```

1. 클라이언트가 `GET /api/messages/poll?lastId=N&timeout=30000` 요청
2. 서버는 새 메시지가 있으면 즉시 응답, 없으면 **응답을 보류하고 대기**
3. (A) 30초 이내에 새 메시지가 오면 → 즉시 해당 메시지로 응답
4. (B) 30초 동안 아무 메시지도 없으면 → 빈 리스트로 응답 (타임아웃)
5. 클라이언트는 응답을 받는 즉시 다시 1번으로 돌아감 (`await` 루프)

---

### 핵심 코드 분석

#### 클라이언트 — await 루프

```javascript
// 핵심: setInterval 없음. await로 응답을 기다렸다가 즉시 재요청
async function startLongPolling() {
    while (isRunning) {
        await longPoll(); // 응답이 올 때까지 여기서 멈춤 (최대 30초)
        // 응답 오면 즉시 다음 줄로 → 즉시 재요청
    }
}

async function longPoll() {
    const res = await fetch(
        `/api/messages/poll?lastId=${lastId}&timeout=30000`,
        { signal: AbortSignal.timeout(35000) }
    );
    const messages = await res.json();

    if (messages.length > 0) {
        messages.forEach(renderMessage);
        lastId = messages[messages.length - 1].id;
    }
    // 빈 배열이어도 함수 종료 → while 루프에서 즉시 재요청
}
```

Short Polling의 `setInterval`이 `while + await`로 바뀐 것이 핵심이다. `await fetch()`는 서버가 응답할 때까지 실행을 멈추므로, 응답이 오기 전까지는 다음 요청이 나가지 않는다. 응답이 오는 즉시 루프를 돌아 바로 다음 요청을 보낸다.

#### 서버 컨트롤러 — DeferredResult 반환

```java
@GetMapping("/messages/poll")
public DeferredResult<List<ChatMessage>> longPoll(    // ← 반환 타입이 핵심
        @RequestParam(defaultValue = "0") long lastId,
        @RequestParam(defaultValue = "30000") long timeout) {

    return chatService.waitForMessages(lastId, timeout);
}
```

반환 타입이 `DeferredResult<List<ChatMessage>>`다. Spring MVC는 이 타입을 받으면 **HTTP 응답을 즉시 보내지 않고** `setResult()`가 호출될 때까지 연결을 유지한다. 컨트롤러 메서드가 반환된 후에도 HTTP 연결은 열려 있다.

Short Polling의 `ResponseEntity`와 결정적으로 다른 지점이 바로 여기다.

#### 서버 서비스 — DeferredResult 핵심 로직

```java
public DeferredResult<List<ChatMessage>> waitForMessages(long lastId, long timeoutMs) {
    DeferredResult<List<ChatMessage>> deferred = new DeferredResult<>(timeoutMs);
    stats.requestArrived();

    // 1단계: 이미 새 메시지 있으면 즉시 응답 (대기 불필요)
    List<ChatMessage> existing = getMessagesSince(lastId);
    if (!existing.isEmpty()) {
        deferred.setResult(existing);               // ← 즉시 응답 전송
        stats.requestCompletedWithMessage();
        return deferred;
    }

    // 2단계: 새 메시지 없음 → 대기 목록에 등록하고 메서드 종료
    //        HTTP 연결은 열린 채로 유지됨
    PendingRequest pending = new PendingRequest(deferred, lastId);
    pendingRequests.add(pending);

    // 3단계: 타임아웃 콜백 등록
    deferred.onTimeout(() -> {
        pendingRequests.remove(pending);
        deferred.setResult(List.of());              // ← 빈 리스트로 응답
        stats.requestTimedOut();
    });

    // 4단계: 연결 끊기면 대기 목록에서 제거
    deferred.onCompletion(() -> pendingRequests.remove(pending));

    return deferred;  // 여기서 반환해도 HTTP 응답은 아직 안 나감
}
```

이 메서드에서 `return deferred`를 하면 Spring MVC 스레드는 반환되지만 HTTP 연결은 살아 있다. `setResult()`가 호출되는 순간에 비로소 HTTP 응답이 클라이언트에게 전송된다.

#### 메시지 전송 시 대기 중인 클라이언트에게 즉시 Push

```java
public ChatMessage sendMessage(String sender, String content) {
    ChatMessage message = ChatMessage.of(idGenerator.incrementAndGet(), sender, content);
    messageStore.add(message);

    // 핵심: 저장 즉시 대기 중인 모든 클라이언트에게 응답 전송
    notifyPendingClients(message.getId());
    return message;
}

private void notifyPendingClients(long newMessageId) {
    List<PendingRequest> toNotify = new ArrayList<>(pendingRequests);
    for (PendingRequest pending : toNotify) {
        List<ChatMessage> messages = getMessagesSince(pending.lastId());
        if (!messages.isEmpty() && !pending.deferred().isSetOrExpired()) {
            pending.deferred().setResult(messages);  // ← 응답 전송
            pendingRequests.remove(pending);
            stats.requestCompletedWithMessage();
        }
    }
}
```

`sendMessage()`가 호출되는 순간 `notifyPendingClients()`가 실행되어 대기 목록의 모든 `DeferredResult`에 `setResult()`를 호출한다. 이 시점에 대기 중이던 클라이언트들이 동시에 메시지를 수신한다.

---

### 주요 객체 설명

#### `DeferredResult<T>`

Spring MVC가 제공하는 비동기 응답 객체다. Long Polling의 핵심이다.

```java
DeferredResult<List<ChatMessage>> deferred = new DeferredResult<>(30000L);
//                                                                  ↑ 타임아웃 (ms)
```

| 메서드 | 설명 |
|--------|------|
| `new DeferredResult<>(timeoutMs)` | 타임아웃 설정. 이 시간 내에 `setResult()`가 호출되지 않으면 타임아웃 처리 |
| `setResult(value)` | 응답을 전송한다. 이 시점에 클라이언트에게 HTTP 응답이 나간다 |
| `onTimeout(callback)` | 타임아웃 발생 시 실행할 콜백 등록 |
| `onCompletion(callback)` | 응답 완료(정상/타임아웃 모두) 후 실행할 콜백 등록 |
| `isSetOrExpired()` | 이미 응답이 전송됐거나 만료됐는지 확인 |

한 번 `setResult()`를 호출하면 그 `DeferredResult`는 재사용할 수 없다. 클라이언트가 다음 요청을 보내면 새로운 `DeferredResult` 객체가 생성된다.

#### `PendingRequest` (내부 레코드)

```java
private record PendingRequest(
    DeferredResult<List<ChatMessage>> deferred,  // 응답을 보낼 채널
    long lastId                                   // 이 클라이언트가 마지막으로 받은 메시지 ID
) {}
```

대기 목록에 등록되는 단위 객체다. `lastId`를 함께 저장하는 이유는 여러 클라이언트가 각자 다른 시점에 연결했을 수 있기 때문이다. 새 메시지가 오면 각 클라이언트의 `lastId` 이후 메시지만 골라서 전달해야 중복이나 누락이 없다.

Java 16+의 `record`로 선언했으므로 `deferred()`와 `lastId()` 접근자가 자동 생성된다.

#### `LongPollingStats`

```java
public class LongPollingStats {
    private final AtomicLong totalRequests      = new AtomicLong(0); // 총 요청 수
    private final AtomicLong timeoutResponses   = new AtomicLong(0); // 타임아웃으로 끝난 요청 수
    private final AtomicLong immediateResponses = new AtomicLong(0); // 메시지로 즉시 응답한 수
    private final AtomicLong pendingRequests    = new AtomicLong(0); // 현재 대기 중인 요청 수
    private final AtomicLong peakPendingRequests = new AtomicLong(0); // 역대 최대 동시 대기 수
    private final AtomicLong totalMessages      = new AtomicLong(0); // 전송된 메시지 수
}
```

Short Polling의 `PollingStats`와 비교해 달라진 지표가 있다.

| 지표 | Short Polling | Long Polling |
|------|---------------|--------------|
| 핵심 낭비 지표 | `emptyResponses` (빈 응답 수) | `timeoutResponses` (타임아웃 수) |
| 서버 홀딩 지표 | 없음 | `pendingRequests` (현재 대기 중) |
| 최고 부하 지표 | 없음 | `peakPendingRequests` |

`pendingRequests`는 현재 서버가 HTTP 연결을 붙잡고 있는 요청 수를 나타낸다. 이 값이 Long Polling에서 새롭게 등장하는 문제의 핵심 지표다. 동시 접속자가 많을수록 이 값이 크고, 각 연결마다 서버 스레드가 점유된다.

#### `ChatService` (2단계)

1단계와 비교해 핵심적으로 추가된 필드가 있다.

```java
// 1단계에는 없었던 필드
private final CopyOnWriteArrayList<PendingRequest> pendingRequests = new CopyOnWriteArrayList<>();
```

이 목록이 Long Polling의 심장이다. 새 메시지가 없어서 대기 중인 모든 클라이언트의 `DeferredResult`가 여기 보관된다. 메시지가 전송되면 이 목록을 순회하며 모두에게 응답을 보낸다.

`CopyOnWriteArrayList`를 선택한 이유는 1단계와 동일하다. 다수의 스레드가 동시에 읽고(여러 클라이언트의 폴링 스레드), 쓰는(메시지 전송 스레드, 타임아웃 콜백 스레드) 상황에서 안전하다.

---

### 문제점

#### 1) 타임아웃마다 TCP 연결 재수립

```
[Long Polling 연결 사이클]
연결 수립 → 대기 → 응답/타임아웃 → 연결 종료 → 연결 수립 → ...
                                        ↑
                               30초마다 반복
```

30초마다 TCP 핸드셰이크가 발생한다. Short Polling처럼 매초 발생하지는 않지만, 연결 재수립 비용 자체는 동일하다.

#### 2) 서버 스레드 점유 문제

```
사용자 1,000명이 동시 접속 (대기 중)
→ pendingRequests 목록에 1,000개의 DeferredResult 등록
→ 각 연결마다 서버 스레드 1개 점유 (Servlet 기반)
→ 스레드 풀 고갈 위험
```

Spring MVC의 기본 스레드 모델에서는 비동기 처리(`DeferredResult`)를 사용해도 연결당 스레드를 완전히 해방하지 못한다. (이 문제는 WebFlux나 WebSocket에서 해결된다.)

#### 3) 서버 Push 불가

클라이언트 → 서버 방향(메시지 전송)은 여전히 별도의 `POST /messages` 요청이 필요하다. 하나의 연결로 양방향 통신을 하는 것이 불가능하다. 이것이 SSE, WebSocket으로 발전하게 되는 직접적인 이유다.

#### 4) 다수 서버 환경 (스케일 아웃) 문제

```
[2대 서버 환경]
Client A → 서버 1에 대기 중
Client B → 서버 2에서 메시지 전송

→ 서버 2는 서버 1의 pendingRequests를 모름
→ Client A는 메시지를 못 받음
```

`pendingRequests` 목록이 서버 인스턴스의 메모리에만 존재하므로, 로드밸런서로 여러 서버에 요청이 분산되면 메시지 유실이 발생한다. 이 문제는 Redis Pub/Sub, Message Queue 등으로 해결해야 한다.

---

## 4. 1단계 vs 2단계 비교

### 동작 방식 비교

```
[Short Polling]
Client ──GET /poll──▶ Server (새 메시지 없음)
Client ◀── [] ────── Server (즉시 빈 응답)
         ← 1초 대기 →
Client ──GET /poll──▶ Server (새 메시지 없음)
Client ◀── [] ────── Server (즉시 빈 응답)
         ← 1초 대기 →
Client ──GET /poll──▶ Server (새 메시지 있음!)
Client ◀── [msg] ─── Server

[Long Polling]
Client ──GET /poll──▶ Server
                       │ (서버가 응답을 보류하고 대기)
                       │
                       │ ← 다른 클라이언트가 메시지 전송
                       │
Client ◀── [msg] ───── Server (즉시 응답)
Client ──GET /poll──▶ Server (받자마자 재요청)
                       │ (다시 대기)
```

### 코드 레벨 비교

| 항목 | Short Polling | Long Polling |
|------|---------------|--------------|
| 컨트롤러 반환 타입 | `ResponseEntity<Map>` | `DeferredResult<List<ChatMessage>>` |
| 서비스 핵심 로직 | 즉시 필터링 후 반환 | DeferredResult 생성 및 대기 목록 등록 |
| 클라이언트 루프 | `setInterval(() => fetch(), 1000)` | `while(true) { await fetch() }` |
| 추가 자료구조 | 없음 | `CopyOnWriteArrayList<PendingRequest>` |
| 통계 핵심 지표 | `emptyResponses`, `wasteRatio` | `pendingRequests`, `timeoutRatio` |

### 시나리오별 성능 비교

**시나리오 1: 5분간 아무도 채팅 안 함 (100명 접속)**

| 구분 | Short Polling (1초 간격) | Long Polling (30초 타임아웃) |
|------|--------------------------|------------------------------|
| HTTP 요청 수 | 100명 × 300초 = **30,000건** | 100명 × 10회(타임아웃) = **1,000건** |
| 빈 응답 수 | **30,000건** (100%) | **1,000건** (타임아웃만) |
| 서버 부하 비율 | 기준 (100%) | **약 3%** |

**시나리오 2: 활발한 채팅 (100명, 초당 1개 메시지)**

| 구분 | Short Polling | Long Polling |
|------|---------------|--------------|
| 메시지 수신 지연 | 평균 500ms (최대 1초) | **~0ms** (즉시 Push) |
| HTTP 요청 수 | 100명 × 60초 = 6,000건 | 100건 + 메시지 수만큼 (대폭 감소) |

### 해결된 문제 vs 남은 문제

| 문제 | Short Polling | Long Polling |
|------|---------------|--------------|
| 빈 응답 낭비 | ❌ 심각 | ✅ 대폭 감소 |
| 메시지 수신 지연 | ❌ 최대 1초 | ✅ ~0ms |
| HTTP 요청 수 | ❌ 매우 많음 | ✅ 크게 감소 |
| TCP 재연결 오버헤드 | ❌ 매초 발생 | ⚠️ 30초마다 발생 |
| 서버 스레드 점유 | ❌ 요청마다 | ❌ 연결 유지 중 점유 |
| 서버 Push (단방향) | ❌ 불가 | ❌ 불가 |
| 완전한 양방향 통신 | ❌ 불가 | ❌ 불가 |
| 다중 서버 환경 | ⚠️ 가능하나 복잡 | ⚠️ 인메모리 공유 불가 |

---

## 5. 공통 구조

두 단계 모두 동일한 공통 구조를 갖는다.

### API 엔드포인트

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `GET` | `/api/messages/poll` | 폴링 엔드포인트 (1·2단계 핵심 차이 지점) |
| `POST` | `/api/messages` | 메시지 전송 |
| `GET` | `/api/messages` | 전체 메시지 조회 (초기 로드) |
| `GET` | `/api/stats` | 통계 조회 |

### 메시지 전송·수신 분리

두 단계 모두 메시지 **수신**(`GET /poll`)과 **전송**(`POST /messages`)이 별도의 HTTP 요청으로 분리되어 있다. 이것이 SSE(3단계)와 WebSocket(4·5단계)과의 근본적 구조 차이다.

```
[1·2단계 공통]
수신 채널: GET  /api/messages/poll  ← 별도 HTTP 요청
송신 채널: POST /api/messages       ← 별도 HTTP 요청

[4·5단계 WebSocket]
수신·송신 채널: ws:// 하나의 연결  ← 단일 연결
```

### `lastId` 기반 증분 조회

두 단계 모두 클라이언트가 마지막으로 받은 메시지의 `id`를 서버에 전달하면, 서버는 그 이후의 메시지만 필터링해서 반환한다. 이 방식 덕분에 중복 수신이 없다.

```
초기 상태:  lastId = 0
메시지 수신: id=1, id=2, id=3 → lastId = 3
다음 요청:  GET /poll?lastId=3
서버 응답:  id > 3 인 메시지만 반환
```

---

## 6. 다음 단계 예고

| 단계 | 기술 | 해결한 문제 |
|------|------|-------------|
| 1단계 | Short Polling | — (기준선) |
| 2단계 | Long Polling | 빈 응답 낭비, 메시지 지연 |
| **3단계** | **SSE** | **TCP 재연결 오버헤드, 연결 유지 스트리밍** |
| **4단계** | **Raw WebSocket** | **양방향 통신, 하나의 연결** |
| **5단계** | **STOMP** | **라우팅 표준화, 브로드캐스트 자동화** |

3단계 SSE에서는 HTTP 연결을 완전히 유지하면서 서버가 클라이언트에게 일방적으로 데이터를 스트리밍한다. Long Polling처럼 타임아웃 후 재연결하는 일이 없어지고, 브라우저가 자동 재연결까지 처리한다. 단 서버→클라이언트 단방향이라는 한계는 여전히 남아 있어 4단계 WebSocket으로 이어진다.