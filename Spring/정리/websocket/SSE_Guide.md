# 3단계 SSE (Server-Sent Events) 상세 정리

## 목차

1. [SSE란 무엇인가](#1-sse란-무엇인가)
2. [동작 흐름](#2-동작-흐름)
3. [SSE 이벤트 포맷 — 텍스트 기반 스트리밍](#3-sse-이벤트-포맷--텍스트-기반-스트리밍)
4. [핵심 객체 상세 설명](#4-핵심-객체-상세-설명)
5. [코드 흐름 분석](#5-코드-흐름-분석)
6. [Heartbeat — 연결 유지 메커니즘](#6-heartbeat--연결-유지-메커니즘)
7. [Last-Event-ID — 재연결과 메시지 복구](#7-last-event-id--재연결과-메시지-복구)
8. [2단계 Long Polling과의 차이점](#8-2단계-long-polling과의-차이점)
9. [문제점 및 한계](#9-문제점-및-한계)
10. [application.yaml 설정 포인트](#10-applicationyaml-설정-포인트)

---

## 1. SSE란 무엇인가

**Server-Sent Events**는 HTTP 연결을 끊지 않고 서버가 클라이언트에게 지속적으로 데이터를 스트리밍하는 기술이다. W3C 표준이며 `Content-Type: text/event-stream` 헤더가 핵심이다.

```
[1단계 Short Polling]  연결 → 응답 → 종료 → 연결 → 응답 → 종료 → ...
[2단계 Long Polling]   연결 ─────────── 응답 → 종료 → 연결 ─────── 응답 → 종료 → ...
[3단계 SSE]            연결 ══════════════════════════════════════════ (연결 유지)
                              이벤트 이벤트 이벤트 이벤트 이벤트 ...
```

1·2단계가 "요청하면 닫히는 수도꼭지"라면, SSE는 "한 번 열면 계속 흘러나오는 수도꼭지"다.

### HTTP 연결이 어떻게 유지되는가

브라우저가 `Content-Type: text/event-stream` 응답을 받으면 "이 연결은 청크 단위로 계속 데이터가 올 것"임을 인식하고, 연결을 닫지 않고 스트리밍 모드로 전환한다. 서버가 `complete()`를 호출하거나 에러가 발생하기 전까지 이 연결은 유지된다.

```
HTTP/1.1 200 OK
Content-Type: text/event-stream   ← 브라우저가 스트리밍 모드로 전환
Cache-Control: no-cache
Connection: keep-alive

id: 1\n
event: message\n
data: {"sender":"Alice","content":"안녕"}\n
\n                                  ← 빈 줄 = 이벤트 경계
id: 2\n
event: message\n
data: {"sender":"Bob","content":"반가워"}\n
\n
: heartbeat\n                      ← 콜론으로 시작 = 코멘트 (이벤트 아님)
\n
```

---

## 2. 동작 흐름

### 전체 흐름 다이어그램

```
클라이언트                                  서버
    │                                        │
    │──── GET /api/subscribe ───────────────▶│
    │     (Last-Event-ID: 0)                 │
    │                                        │ SseEmitter 생성
    │                                        │ clients 맵에 등록
    │◀─── HTTP 200 (text/event-stream) ──────│
    │◀─── event:connected ───────────────────│ (연결 확인 이벤트)
    │                                        │
    │     [연결 유지 중]                      │
    │                                        │
    │                  다른 클라이언트가 POST /api/messages
    │                                        │ sendMessage() 호출
    │                                        │ broadcastMessage() 실행
    │◀─── event:message, data:{...} ─────────│ (즉시 Push)
    │◀─── event:message, data:{...} ─────────│ (즉시 Push)
    │                                        │
    │     [연결 유지 중]                      │
    │                                        │
    │◀─── : heartbeat ───────────────────────│ (15초마다)
    │                                        │
    │     [브라우저 탭 닫기]                  │
    │     onCompletion 콜백 실행             │
    │                                        │ clients 맵에서 제거
```

### 재연결 흐름

```
클라이언트                                  서버
    │                                        │
    │     [네트워크 끊김 또는 서버 재시작]    │
    │     (브라우저가 자동 재연결 시도)       │
    │                                        │
    │──── GET /api/subscribe ───────────────▶│
    │     (Last-Event-ID: 5)  ←──────────────── 마지막 수신 이벤트 ID 자동 첨부
    │                                        │
    │                                        │ replayMissedMessages() 실행
    │◀─── event:message id:6 ────────────────│ (id:5 이후 메시지 재전송)
    │◀─── event:message id:7 ────────────────│
    │◀─── event:message id:8 ────────────────│
    │                                        │ (정상 스트리밍 재개)
```

---

## 3. SSE 이벤트 포맷 — 텍스트 기반 스트리밍

SSE는 순수 텍스트 기반 프로토콜이다. 각 이벤트는 `필드명: 값\n` 형태의 줄들로 구성되며, 빈 줄(`\n\n`)로 이벤트 하나가 끝난다.

### 이벤트 필드

| 필드 | 의미 | 예시 |
|------|------|------|
| `id` | 이벤트 고유 ID. 브라우저가 `Last-Event-ID`로 기억 | `id: 42` |
| `event` | 이벤트 타입. 클라이언트의 `addEventListener` 이름 | `event: message` |
| `data` | 실제 페이로드. 여러 줄이면 `data:` 를 반복 | `data: {"key":"value"}` |
| `retry` | 재연결 대기 시간(ms). 브라우저 기본값 3000ms | `retry: 5000` |
| `: 코멘트` | 콜론으로 시작하는 줄은 코멘트. 이벤트로 처리 안 됨 | `: heartbeat` |

### 실제 전송되는 텍스트 예시

```
id: 1\n
event: connected\n
data: {"clientId":"abc123","message":"SSE 연결 수립 완료"}\n
\n
id: 2\n
event: message\n
data: {"id":2,"sender":"Alice","content":"안녕하세요","timestamp":"12:34:56.789"}\n
\n
: heartbeat\n
\n
id: 3\n
event: message\n
data: {"id":3,"sender":"Bob","content":"반갑습니다","timestamp":"12:35:10.123"}\n
\n
```

---

## 4. 핵심 객체 상세 설명

### `SseEmitter`

> `org.springframework.web.servlet.mvc.method.annotation.SseEmitter`

SSE의 핵심 클래스. Spring MVC에서 제공한다. **하나의 HTTP 연결을 통해 여러 번 데이터를 전송할 수 있는 채널**이다.

2단계의 `DeferredResult`와 겉보기엔 비슷하지만 근본적으로 다르다.

```
DeferredResult  : 한 번만 setResult() 가능 → 응답 후 객체 소멸
SseEmitter      : send() 를 무제한 반복 가능 → complete() 호출 전까지 유지
```

#### 생성

```java
// 타임아웃 설정
SseEmitter emitter = new SseEmitter(30_000L);  // 30초 타임아웃
SseEmitter emitter = new SseEmitter(0L);        // 타임아웃 없음 (무제한)
SseEmitter emitter = new SseEmitter();          // 기본값 (Spring 기본: 30초)
```

타임아웃 `0L`은 Spring 레벨에서는 무제한이지만, 브라우저·프록시·로드밸런서가 임의로 끊을 수 있다. 따라서 Heartbeat와 함께 사용해야 한다.

#### `send()` — 이벤트 전송

```java
// 방법 1: 단순 데이터 전송 (event 이름 없음)
emitter.send("텍스트 데이터");
emitter.send(someObject);  // Jackson이 JSON으로 직렬화

// 방법 2: SseEmitter.SseEventBuilder 로 상세 구성
emitter.send(
    SseEmitter.event()
        .id("42")           // Last-Event-ID 값
        .name("message")    // 이벤트 타입 (addEventListener 이름)
        .data(chatMessage)  // 페이로드 (자동 JSON 직렬화)
        .reconnectTime(3000L) // 재연결 대기 시간 (선택)
);

// 방법 3: 코멘트 전송 (Heartbeat용)
emitter.send(
    SseEmitter.event()
        .comment("heartbeat")  // ": heartbeat\n\n" 형태로 전송
);
```

#### 콜백 등록

```java
emitter.onCompletion(() -> {
    // 정상 종료(complete() 호출) 또는 클라이언트가 연결 끊었을 때
    clients.remove(clientId);
});

emitter.onTimeout(() -> {
    // 타임아웃 발생 시
    emitter.complete();  // 명시적으로 닫기
    clients.remove(clientId);
});

emitter.onError(throwable -> {
    // 전송 오류 시
    clients.remove(clientId);
});
```

> **주의:** 콜백들은 다른 스레드에서 실행될 수 있다. 공유 자원 접근 시 동시성을 고려해야 한다.

#### `complete()` vs `completeWithError()`

```java
emitter.complete();                          // 정상 종료 (연결 닫기)
emitter.completeWithError(new Exception()); // 에러로 종료
```

`complete()`를 호출하면 클라이언트 측의 `EventSource`가 `error` 이벤트를 받고, 이후 브라우저가 자동 재연결을 시도한다.

---

### `SseEmitter.SseEventBuilder`

> `SseEmitter.event()`로 생성하는 빌더 객체

SSE 이벤트 하나를 구성하는 빌더 패턴 클래스. 정적 팩토리 메서드 `SseEmitter.event()`로 인스턴스를 만든다.

```java
SseEmitter.SseEventBuilder builder = SseEmitter.event();
```

#### 메서드 체인

| 메서드 | 전송되는 텍스트 | 설명 |
|--------|----------------|------|
| `.id("42")` | `id: 42\n` | Last-Event-ID에 사용 |
| `.name("message")` | `event: message\n` | 이벤트 타입 지정 |
| `.data(object)` | `data: {...}\n` | 페이로드. 객체는 Jackson으로 JSON 직렬화 |
| `.data("text", MediaType.TEXT_PLAIN)` | `data: text\n` | 미디어 타입 지정 |
| `.comment("ping")` | `: ping\n` | 코멘트 라인. 이벤트로 처리 안 됨 |
| `.reconnectTime(3000L)` | `retry: 3000\n` | 재연결 대기 시간(ms) |

모든 메서드는 `SseEventBuilder`를 반환하므로 체이닝이 가능하다.

---

### `EventSource` (클라이언트 — 브라우저 API)

> `new EventSource(url)` — JavaScript 브라우저 내장 API

클라이언트 측에서 SSE 연결을 관리하는 브라우저 내장 객체다. Spring의 `SseEmitter`와 짝을 이루는 클라이언트 측 핵심이다.

```javascript
// 연결 수립
const eventSource = new EventSource('/api/subscribe');

// 기본 메시지 수신 (event 이름 없는 경우)
eventSource.onmessage = (e) => {
    console.log(e.data);  // 문자열로 들어옴. JSON이면 파싱 필요
};

// 특정 이벤트 타입 수신 (.name("message") 로 보낸 것)
eventSource.addEventListener('message', (e) => {
    const msg = JSON.parse(e.data);
});

// 연결 성공 이벤트
eventSource.addEventListener('connected', (e) => {
    const data = JSON.parse(e.data);
    console.log('clientId:', data.clientId);
});

// 에러 / 재연결 감지
eventSource.onerror = () => {
    // 브라우저가 자동으로 재연결 시도 중
};

// 연결 상태 확인
// EventSource.CONNECTING = 0
// EventSource.OPEN       = 1
// EventSource.CLOSED     = 2
console.log(eventSource.readyState);

// 연결 닫기 (자동 재연결 중지)
eventSource.close();
```

#### `EventSource`의 자동 재연결

`EventSource`는 연결이 끊기면 **브라우저가 자동으로 재연결**한다. 이것이 Long Polling과의 중요한 차이다.

| 구분 | Long Polling | SSE (EventSource) |
|------|-------------|-------------------|
| 재연결 처리 | 개발자가 직접 `while` 루프 | 브라우저 자동 처리 |
| Last-Event-ID 전송 | 개발자가 직접 파라미터로 전달 | 브라우저 자동 헤더 첨부 |
| 재연결 대기 시간 | 코드로 직접 설정 | `retry:` 필드 또는 기본 3초 |

---

### `MediaType.TEXT_EVENT_STREAM_VALUE`

> `org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE`

`"text/event-stream"` 문자열 상수다. 컨트롤러의 `produces` 속성에 사용한다.

```java
@GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter subscribe(...) { ... }
```

이 설정이 없으면 Spring이 응답 Content-Type을 `application/json`으로 설정해버려 브라우저가 SSE로 인식하지 못한다.

---

### `@RequestHeader("Last-Event-ID")`

```java
@GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter subscribe(
        @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long lastEventId) {
```

브라우저는 `EventSource`가 재연결할 때 마지막으로 수신한 이벤트의 `id` 값을 `Last-Event-ID` HTTP 헤더에 자동으로 담아 전송한다. 서버는 이 헤더를 읽어 해당 ID 이후의 메시지를 재전송할 수 있다.

최초 연결 시에는 이 헤더가 없으므로 `defaultValue = "0"`으로 폴백한다.

---

### `@Scheduled`

> `org.springframework.scheduling.annotation.Scheduled`

메서드를 주기적으로 자동 실행시키는 어노테이션이다. Heartbeat 전송에 사용했다.

```java
@Scheduled(fixedDelay = 15000)  // 이전 실행 완료 후 15초 뒤에 다음 실행
public void sendHeartbeat() { ... }

// 참고: fixedRate는 이전 실행 시작 기준으로 15초마다 (겹칠 수 있음)
// @Scheduled(fixedRate = 15000)

// cron 표현식도 가능
// @Scheduled(cron = "0/15 * * * * *")
```

`@Scheduled`를 사용하려면 메인 클래스에 `@EnableScheduling`이 선언되어 있어야 한다.

```java
@SpringBootApplication
@EnableScheduling  // ← 없으면 @Scheduled가 동작하지 않음
public class SseChatApplication { ... }
```

---

### `ConcurrentHashMap`

> `java.util.concurrent.ConcurrentHashMap`

`SseService`에서 클라이언트 목록을 관리하는 맵이다.

```java
private final Map<String, SseClient> clients = new ConcurrentHashMap<>();
```

일반 `HashMap`을 쓰면 안 되는 이유가 있다. SSE 서버는 다음 상황이 동시에 발생한다.

- **여러 클라이언트가 동시에 연결** → 여러 스레드가 동시에 `put()` 호출
- **Heartbeat 스케줄러가 순회** → 별도 스레드에서 `entrySet()` 순회 중
- **메시지 전송 시 순회** → `broadcastMessage()`에서 순회 중
- **클라이언트가 끊겼을 때 제거** → 콜백 스레드에서 `remove()` 호출

일반 `HashMap`에서 이런 동시 접근이 일어나면 `ConcurrentModificationException`이나 데이터 손상이 발생한다. `ConcurrentHashMap`은 내부적으로 세그먼트 락(Java 8+에서는 노드 수준 락)을 사용해 이를 안전하게 처리한다.

Long Polling의 `pendingRequests`에 `CopyOnWriteArrayList`를 쓴 것과 달리 `ConcurrentHashMap`을 선택한 이유는, clientId로 특정 클라이언트를 **O(1)로 빠르게 조회·삭제**해야 하기 때문이다.

---

## 5. 코드 흐름 분석

### 연결 수립 흐름

```java
// 1. 컨트롤러: SseEmitter를 반환
@GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter subscribe(
        @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long lastEventId) {
    return sseService.subscribe(lastEventId);
    // Spring은 SseEmitter를 받으면 연결을 유지한 채로 메서드에서 반환
}

// 2. 서비스: SseEmitter 생성 및 등록
public SseEmitter subscribe(long lastEventId) {
    String clientId = UUID.randomUUID().toString().substring(0, 8);
    SseEmitter emitter = new SseEmitter(0L);    // 타임아웃 없음
    SseClient client = new SseClient(clientId, emitter, lastEventId);

    clients.put(clientId, client);  // 맵에 등록 (이후 브로드캐스트에서 사용)

    // 연결 해제 콜백 3종 등록
    emitter.onCompletion(() -> removeClient(clientId, "completion"));
    emitter.onTimeout(   () -> removeClient(clientId, "timeout"));
    emitter.onError(   e -> removeClient(clientId, "error"));

    // 연결 확인 이벤트 전송
    sendEvent(emitter, "connected", Map.of("clientId", clientId));

    // 재연결 시 놓친 메시지 재전송
    if (lastEventId > 0) {
        replayMissedMessages(client);
    }

    return emitter;  // 컨트롤러로 반환 → Spring이 연결 유지
}
```

`return emitter`를 하는 시점에 HTTP 응답 헤더(`Content-Type: text/event-stream`)가 전송되고 연결이 스트리밍 모드로 전환된다. 이후 `emitter.send()`를 호출할 때마다 데이터가 스트리밍된다.

### 메시지 브로드캐스트 흐름

```java
public ChatMessage sendMessage(String sender, String content) {
    // 1. 메시지 저장
    ChatMessage message = ChatMessage.of(idGenerator.incrementAndGet(), sender, content);
    messageStore.add(message);

    // 2. 모든 연결된 클라이언트에게 즉시 Push
    broadcastMessage(message);
    return message;
}

private void broadcastMessage(ChatMessage message) {
    List<String> deadClients = new ArrayList<>();

    for (Map.Entry<String, SseClient> entry : clients.entrySet()) {
        try {
            // SseEventBuilder로 이벤트 구성 후 전송
            entry.getValue().getEmitter().send(
                SseEmitter.event()
                    .id(String.valueOf(message.getId()))  // Last-Event-ID용
                    .name("message")                       // event 필드
                    .data(message)                         // data 필드 (JSON 직렬화)
            );
        } catch (IOException e) {
            // 전송 실패 = 끊긴 연결
            deadClients.add(entry.getKey());
        }
    }

    // 끊긴 연결 정리
    deadClients.forEach(id -> removeClient(id, "send failed"));
}
```

Long Polling의 `notifyPendingClients()`와 구조는 비슷하지만 결정적으로 다른 점이 있다.

- **Long Polling:** `setResult()` 호출 → 해당 `DeferredResult` 소멸 → 클라이언트가 재요청
- **SSE:** `send()` 호출 → `SseEmitter` 유지 → 같은 연결로 다음 메시지도 전송

---

## 6. Heartbeat — 연결 유지 메커니즘

### 왜 필요한가

HTTP 연결 중간에는 다양한 인프라 장비가 있다.

```
브라우저 → 방화벽 → 로드밸런서 → Nginx 리버스 프록시 → Spring 서버
```

이 장비들은 대부분 "일정 시간 동안 데이터가 없으면 유휴 연결로 판단하고 강제 종료"하는 타임아웃이 있다. AWS ALB 기본값이 60초, Nginx 기본값이 75초다.

채팅이 조용한 시간에는 이 타임아웃에 걸려 SSE 연결이 강제로 끊길 수 있다. 이를 방지하기 위해 주기적으로 데이터를 전송한다.

### SSE 코멘트 라인 (`": 텍스트"`)

Heartbeat에는 SSE 코멘트 라인을 사용한다. 콜론(`:`)으로 시작하는 줄은 브라우저의 `EventSource`에서 이벤트로 처리되지 않는다. 즉 클라이언트 코드에서 아무 동작도 하지 않지만, TCP 레벨에서는 데이터가 전송되었으므로 중간 장비들의 타임아웃 카운터가 리셋된다.

```java
@Scheduled(fixedDelay = 15000) // 15초마다
public void sendHeartbeat() {
    for (Map.Entry<String, SseClient> entry : clients.entrySet()) {
        try {
            entry.getValue().getEmitter().send(
                SseEmitter.event().comment("heartbeat")
                // 실제 전송 텍스트: ": heartbeat\n\n"
                // 브라우저 EventSource는 이것을 이벤트로 처리하지 않음
            );
        } catch (IOException e) {
            deadClients.add(entry.getKey()); // 전송 실패 = 끊긴 연결 감지
        }
    }
}
```

Heartbeat 전송 실패(`IOException`)는 곧 해당 클라이언트의 연결이 끊겼다는 신호이기도 하다. 이를 이용해 Dead Connection을 감지한다.

### Long Polling과의 비교

Long Polling에는 Heartbeat 개념이 없다. 30초 타임아웃 자체가 주기적 재연결을 강제하므로 중간 장비의 타임아웃보다 짧게 유지된다. 타임아웃마다 재연결을 반복하는 것이 일종의 Heartbeat 역할을 한다.

SSE는 연결을 유지하는 방식이므로 명시적인 Heartbeat가 반드시 필요하다.

---

## 7. Last-Event-ID — 재연결과 메시지 복구

SSE의 중요한 기능 중 하나다. 브라우저가 자동 재연결할 때 메시지를 놓치지 않도록 돕는 메커니즘이다.

### 동작 원리

```
1. 서버가 이벤트를 전송할 때 id 필드를 포함:
   id: 5
   event: message
   data: {...}

2. 브라우저의 EventSource가 id:5 를 내부적으로 기억

3. 연결이 끊기면 브라우저가 재연결 시도:
   GET /api/subscribe
   Last-Event-ID: 5    ← 자동으로 헤더에 포함

4. 서버가 헤더를 읽어 id > 5 인 메시지를 재전송
```

### 서버 측 구현

```java
// 컨트롤러: Last-Event-ID 헤더 읽기
@GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter subscribe(
        @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long lastEventId) {
    return sseService.subscribe(lastEventId);
}

// 서비스: 놓친 메시지 재전송
private void replayMissedMessages(SseClient client) {
    List<ChatMessage> missed = messageStore.stream()
            .filter(m -> m.getId() > client.getLastEventId()) // lastEventId 이후
            .toList();

    for (ChatMessage msg : missed) {
        client.getEmitter().send(
            SseEmitter.event()
                .id(String.valueOf(msg.getId()))
                .name("message")
                .data(msg)
        );
    }
}
```

### Long Polling과의 비교

Long Polling도 `lastId` 파라미터로 증분 조회를 했지만, 그것은 개발자가 직접 구현한 것이다. SSE + `EventSource`에서는 `id` 필드와 `Last-Event-ID` 헤더가 표준으로 정의되어 있어 브라우저가 자동 처리한다.

---

## 8. 2단계 Long Polling과의 차이점

### 핵심 구조 차이

```
[Long Polling 수신 구조]
요청 → 대기 → 응답(연결 종료) → 재요청 → 대기 → 응답(연결 종료) → ...
       DeferredResult          새 DeferredResult

[SSE 수신 구조]
요청 → 연결 수립 → 이벤트 → 이벤트 → 이벤트 → ... (연결 유지)
       SseEmitter
```

### 코드 레벨 비교

| 항목 | Long Polling | SSE |
|------|-------------|-----|
| 서버 반환 타입 | `DeferredResult<List<ChatMessage>>` | `SseEmitter` |
| 응답 전송 방식 | `deferred.setResult(messages)` | `emitter.send(event)` |
| 전송 횟수 | 1회만 가능 (이후 소멸) | 무제한 반복 가능 |
| 연결 수명 | 응답 후 즉시 종료 | `complete()` 전까지 유지 |
| 클라이언트 루프 | `while(true) { await fetch() }` | `new EventSource(url)` |
| 재연결 처리 | 개발자 직접 구현 | 브라우저 자동 처리 |
| Last-Event-ID | 개발자가 파라미터로 전달 | 브라우저가 헤더로 자동 전달 |
| Heartbeat 필요 여부 | 불필요 (30초마다 재연결) | 필요 (연결 유지 중 타임아웃 방지) |
| 데이터 저장소 | `CopyOnWriteArrayList<PendingRequest>` | `ConcurrentHashMap<String, SseClient>` |
| Content-Type | `application/json` | `text/event-stream` |

### 메시지 지연 비교

두 방식 모두 메시지 수신 지연은 거의 0이다. 하지만 내부 동작 방식이 다르다.

**Long Polling:**
- 메시지가 오면 → `DeferredResult.setResult()` → HTTP 응답 전송 → 연결 종료
- 클라이언트가 응답을 받고 즉시 재요청 → 새 연결 수립 (TCP 핸드셰이크)
- 다음 메시지는 새 연결로 수신

**SSE:**
- 메시지가 오면 → `emitter.send()` → 데이터 청크 전송 → 연결 유지
- 다음 메시지도 같은 연결로 수신
- TCP 재연결 없음

### 연결 수 비교 — 5분 동안 10개 메시지, 100명 접속

| 구분 | Long Polling | SSE |
|------|-------------|-----|
| HTTP 연결 수립 횟수 | 100명 × (10회 메시지 응답 + 10회 타임아웃) = **2,000회** | **100회** (최초 연결 1회) |
| TCP 핸드셰이크 | **2,000번** | **100번** |
| 서버에서 관리하는 객체 | 현재 대기 중인 `DeferredResult` 목록 | 연결된 `SseEmitter` 맵 |

### application.yaml 설정 차이

```yaml
# Long Polling
spring:
  mvc:
    async:
      request-timeout: 35000  # 35초 (클라이언트 30초 타임아웃 + 여유)

# SSE
spring:
  mvc:
    async:
      request-timeout: -1     # 무제한 (-1)
      # 기본값 30초를 그대로 두면 SSE 연결이 30초마다 끊김
```

---

## 9. 문제점 및 한계

### 1) 단방향 — 가장 근본적인 한계

SSE는 **서버 → 클라이언트** 방향만 가능하다. 클라이언트 → 서버는 여전히 별도의 HTTP POST가 필요하다.

```
[SSE 구조]
수신: GET /api/subscribe (SSE 스트림, 유지)
송신: POST /api/messages  (별도 HTTP 요청)

수신과 송신이 두 개의 별개 연결을 사용
```

이것이 4단계 WebSocket으로 발전하는 직접적인 이유다. WebSocket은 하나의 연결로 양방향 통신이 가능하다.

### 2) 브라우저 연결 수 제한

HTTP/1.1에서 브라우저는 **같은 도메인에 대해 최대 6개의 연결**만 허용한다. SSE 연결 하나를 점유하면 같은 도메인의 다른 HTTP 요청에 사용 가능한 연결이 5개로 줄어든다.

여러 탭에서 같은 SSE 엔드포인트를 열면 6개를 초과해 연결이 대기 상태가 된다.

> HTTP/2를 사용하면 다중화(Multiplexing)로 이 제한이 사실상 없어진다.

### 3) 중간 프록시 문제

일부 기업 방화벽이나 구형 프록시 서버는 `text/event-stream` 응답을 버퍼링하거나 차단한다. SSE 이벤트가 즉시 전달되지 않고 프록시에 쌓였다가 한 번에 전달되는 경우가 있다.

### 4) 서버 메모리 증가

Long Polling은 응답 후 `DeferredResult`가 소멸되지만, SSE는 클라이언트가 연결을 유지하는 동안 `SseEmitter` 객체와 `SseClient` 객체가 메모리에 계속 남는다. 동시 접속자가 많을수록 메모리 사용량이 늘어난다.

### 5) 다중 서버 환경 — Long Polling과 동일한 문제

```
[2대 서버 환경]
Client A → 서버 1에 SSE 연결 중
Client B → 서버 2에서 메시지 전송

→ 서버 2의 broadcastMessage()는 서버 2의 clients 맵만 순회
→ 서버 1에 연결된 Client A는 메시지를 못 받음
```

`clients` 맵이 서버 인스턴스 메모리에 있으므로, 로드밸런서 환경에서 서버가 여러 대면 메시지 유실이 발생한다. 해결책으로 Redis Pub/Sub, Kafka 등의 메시지 브로커를 사이에 두어야 한다.

---

## 10. application.yaml 설정 포인트

```yaml
spring:
  mvc:
    async:
      request-timeout: -1
```

이 설정이 없으면 Spring MVC의 기본 비동기 요청 타임아웃(30초)이 적용되어 SSE 연결이 30초마다 자동으로 끊긴다. `-1`은 Spring 레벨의 타임아웃을 비활성화한다는 의미다.

단, 이것은 Spring 레벨의 설정이다. 브라우저, 프록시, 로드밸런서의 타임아웃은 별개로 존재하므로 Heartbeat로 대응해야 한다.

---

## 정리

| 항목 | 1단계 Short Polling | 2단계 Long Polling | 3단계 SSE |
|------|---------------------|-------------------|-----------|
| 연결 수명 | 매 요청 새로 | 30초 유지 | 영구 유지 |
| 빈 응답 | 매우 많음 | 타임아웃만 | 없음 |
| 메시지 지연 | 최대 1초 | ~0ms | ~0ms |
| TCP 재연결 | 초당 N회 | 30초마다 | 재연결 없음 |
| 재연결 처리 | 개발자 직접 | 개발자 직접 | 브라우저 자동 |
| 핵심 Spring 객체 | `ResponseEntity` | `DeferredResult` | `SseEmitter` |
| 클라이언트 API | `fetch` + `setInterval` | `fetch` + `while` | `EventSource` |
| 양방향 통신 | ❌ | ❌ | ❌ |
| 서버 Push | ❌ (폴링) | ⚠️ (응답 후 종료) | ✅ (스트리밍) |

**3단계에서 해결한 것:** TCP 재연결 오버헤드, 브라우저 자동 재연결, 메시지 유실 복구(`Last-Event-ID`)

**여전히 남은 문제:** 단방향 통신 (4단계 WebSocket으로 해결)