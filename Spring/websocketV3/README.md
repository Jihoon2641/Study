# websocketV3 — SSE(Server-Sent Events) 채팅

> 실시간 통신 구현 방식을 단계적으로 학습하는 시리즈의 **3단계**

---

## 📌 학습 목표

| 단계 | 기술 | 핵심 개념 |
|------|------|-----------|
| 1단계 | Short Polling | 주기적 HTTP 요청 |
| 2단계 | Long Polling | `DeferredResult`로 대기 후 응답 |
| **3단계** ← 현재 | **SSE** | `SseEmitter`로 단일 연결 유지 + 스트리밍 Push |
| 4단계 | WebSocket | 완전한 양방향 통신 |

---

## 🚀 실행 방법

### 사전 요구사항
- Java 17
- Gradle (Wrapper 포함)

### 서버 실행
```bash
cd websocketV3
./gradlew bootRun
```

### 접속
- **채팅 UI**: http://localhost:8080
- **포트 충돌 시**: `lsof -ti:8080 | xargs kill -9`

---

## 🏗️ 프로젝트 구조

```
websocketV3/
├── src/main/
│   ├── java/com/study/websocketV3/
│   │   ├── WebsocketV3Application.java     # Spring Boot 진입점
│   │   │
│   │   ├── controller/
│   │   │   └── ChatController.java         # REST API 엔드포인트
│   │   │
│   │   ├── service/
│   │   │   └── SseService.java             # SSE 핵심 비즈니스 로직
│   │   │
│   │   ├── sse/
│   │   │   └── SseClient.java              # SSE 연결 단위 모델
│   │   │
│   │   ├── dto/
│   │   │   └── ChatMessage.java            # 메시지 데이터 구조
│   │   │
│   │   └── actuator/
│   │       └── SseStats.java               # 연결 통계 수집
│   │
│   └── resources/
│       ├── application.yaml                # 서버 설정
│       └── static/
│           └── index.html                  # 채팅 + 모니터링 UI
│
└── build.gradle
```

---

## 📡 SSE 핵심 개념

### SSE(Server-Sent Events)란?

```
[클라이언트]                          [서버]
     │                                  │
     │── GET /api/subscribe ──────────▶│  SseEmitter 생성
     │                                  │
     │◀── HTTP 200 (헤더만, body 열림) ─│  연결 유지
     │                                  │
     │   (연결이 살아있는 상태)          │
     │                                  │
     │◀── id:1\nevent:message\ndata:…──│  emitter.send()
     │◀── id:2\nevent:message\ndata:…──│  emitter.send()
     │◀── : heartbeat\n\n ─────────────│  15초마다 연결 유지
     │                                  │
     │◀── (연결 종료) ─────────────────│  emitter.complete()
```

### Long Polling(2단계) vs SSE(3단계)

| 항목 | Long Polling (DeferredResult) | SSE (SseEmitter) |
|------|-------------------------------|------------------|
| 응답 후 연결 | ❌ 종료 → 재연결 필요 | ✅ 유지 |
| 메시지 전송 횟수 | 1회 후 제거 | 무제한 (complete() 전까지) |
| 보관 방식 | `List<DeferredResult>` (응답 후 제거) | `Map<id, SseClient>` (계속 유지) |
| 빈 응답 | 타임아웃 시 발생 | 없음 |
| 재연결 | 수동 구현 필요 | 브라우저 자동 처리 |

### SSE HTTP 응답 포맷

```
Content-Type: text/event-stream
Cache-Control: no-cache

id: 1
event: message
data: {"id":1,"sender":"철수","content":"안녕","timestamp":"13:07:01.234"}

id: 2
event: message
data: {"id":2,"sender":"영희","content":"반가워","timestamp":"13:07:05.678"}

: heartbeat

```

> 이벤트는 반드시 **빈 줄(`\n\n`)** 로 구분됩니다.

---

## 🗂️ 클래스별 상세 설명

### 1. `ChatController` — REST API

```
GET  /api/subscribe    →  SSE 스트림 연결 (text/event-stream)
POST /api/messages     →  메시지 전송
GET  /api/messages     →  전체 메시지 조회
GET  /api/stats        →  연결 통계 조회
```

**SSE의 근본적 한계**: SSE는 서버 → 클라이언트 **단방향**  
클라이언트 → 서버 전송은 여전히 별도의 `HTTP POST` 필요  
→ 4단계 WebSocket으로 완전한 양방향 해결

```java
@GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter subscribe(
    @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long lastEventId
) {
    return sseService.subscribe(lastEventId);
}
```

---

### 2. `SseService` — 핵심 비즈니스 로직

#### 핵심 자료구조

```java
// 연결된 모든 클라이언트 보관 (영속적으로 유지)
Map<String, SseClient> clients = new ConcurrentHashMap<>();

// 메시지 저장소 (재연결 시 누락 메시지 재전송용)
CopyOnWriteArrayList<ChatMessage> messageStore;

// 단조 증가하는 메시지 ID (Last-Event-ID 추적용)
AtomicLong idGenerator;
```

> **왜 `ConcurrentHashMap`?**  
> 여러 스레드(각 HTTP 요청마다 Tomcat 스레드)가 동시에 클라이언트를 추가/제거하므로 스레드 세이프한 자료구조 필요

#### `subscribe()` — 연결 수립

```java
public SseEmitter subscribe(long lastEventId) {
    SseEmitter emitter = new SseEmitter(0L);  // 0L = 타임아웃 없음

    clients.put(clientId, client);
    stats.connected();   // 통계 카운터 증가

    // 연결 해제 시 자동 정리
    emitter.onCompletion(() -> removeClient(clientId, "completion"));
    emitter.onTimeout(   () -> removeClient(clientId, "timeout"));
    emitter.onError(   e -> removeClient(clientId, "error"));

    // 재연결: Last-Event-ID 이후 누락된 메시지 재전송
    if (lastEventId > 0) {
        replayMissedMessage(client);
    }

    return emitter;
}
```

#### `broadcastMessage()` — 전체 Push

```java
// 모든 클라이언트에게 동시 전송
for (SseClient client : clients.values()) {
    client.getEmitter().send(
        SseEmitter.event()
            .id(String.valueOf(message.getId()))  // Last-Event-ID 추적
            .name("message")                       // 이벤트 타입
            .data(message)                         // JSON 자동 직렬화
    );
}
```

#### `sendHeartbeat()` — 연결 유지 (@Scheduled)

```java
@Scheduled(fixedDelay = 15000)  // 15초마다 실행
public void sendHeartbeat() {
    // SSE 코멘트 라인: ": heartbeat\n\n"
    // → 클라이언트에서 onmessage 이벤트 발생 안 함 (순수 연결 유지용)
    emitter.send(SseEmitter.event().comment("heartbeat"));
}
```

> **왜 Heartbeat가 필요한가?**  
> HTTP 프록시, 로드밸런서, 방화벽은 일정 시간 데이터 전송이 없으면  
> 연결을 강제 종료합니다. 주기적인 코멘트 전송으로 이를 방지합니다.

---

### 3. `SseClient` — 연결 단위 모델

```java
public class SseClient {
    private final String clientId;      // UUID 앞 8자리
    private final SseEmitter emitter;   // 실제 HTTP 스트림
    private final String connectedAt;   // 연결 시각 (HH:mm:ss)
    private long lastEventId;           // 마지막 수신 이벤트 ID
}
```

Long Polling의 `DeferredResult`와의 차이:
- `DeferredResult`: 응답 1회 → 즉시 제거
- `SseClient`: `complete()` 호출 전까지 **영구 보존**

---

### 4. `ChatMessage` — 메시지 DTO

```java
public class ChatMessage {
    private long id;          // 단조 증가 ID (Last-Event-ID 추적용)
    private String sender;    // 발신자
    private String content;   // 내용
    private String timestamp; // 생성 시각 (HH:mm:ss.SSS)
}
```

---

### 5. `SseStats` — 연결 통계

`AtomicLong`을 사용한 스레드 세이프 카운터들:

| 필드 | 설명 |
|------|------|
| `totalConnections` | 총 SSE 연결 수립 횟수 (재연결 포함) |
| `activeStreams` | 현재 활성 스트림 수 |
| `peakActiveStreams` | 역대 최대 동시 활성 수 |
| `disconnections` | 연결 종료 횟수 |
| `messageEventsSent` | 실제 Push된 메시지 이벤트 수 |
| `heartbeatsSent` | Heartbeat 전송 횟수 |
| `totalMessages` | 서버에 저장된 메시지 총 수 |

`GET /api/stats`로 실시간 확인 가능

---

## 🔑 Last-Event-ID 메커니즘

브라우저가 SSE 연결 끊김을 감지하면 **자동 재연결** 시도:

```
[정상 수신 중]
서버 → id:5, id:6, id:7 전송

[네트워크 끊김]
브라우저: "마지막으로 받은 게 id=7"

[자동 재연결]
GET /api/subscribe
Last-Event-ID: 7   ← 브라우저가 자동으로 헤더에 포함

[서버의 replayMissedMessage()]
messageStore에서 id > 7 인 메시지 찾아 재전송
→ 메시지 누락 없음!
```

---

## 💓 Heartbeat 동작 흐름

```
t=0s   연결 수립
t=15s  ": heartbeat\n\n" 전송  → 프록시/방화벽이 "살아있다!" 인식
t=30s  ": heartbeat\n\n" 전송
t=45s  ": heartbeat\n\n" 전송
...
```

클라이언트(브라우저)는 코멘트 라인을 무시하므로 `onmessage` 이벤트 미발생

---

## 🌐 API 명세

### `GET /api/subscribe`
SSE 스트림 구독

| 항목 | 값 |
|------|----|
| Request Header | `Last-Event-ID: {number}` (선택, 재연결 시) |
| Response Content-Type | `text/event-stream` |

**연결 직후 이벤트:**
```
event: connected
data: {"clientId":"0aaba389","message":"SSE 연결 수립 완료"}
```

**메시지 수신 이벤트:**
```
id: 1
event: message
data: {"id":1,"sender":"철수","content":"안녕","timestamp":"13:07:01.234"}
```

---

### `POST /api/messages`
메시지 전송

**Request Body:**
```json
{ "sender": "철수", "content": "안녕하세요" }
```

**Response:**
```json
{ "id": 1, "sender": "철수", "content": "안녕하세요", "timestamp": "13:07:01.234" }
```

---

### `GET /api/messages`
전체 메시지 조회

---

### `GET /api/stats`
연결 통계 조회

```json
{
  "totalConnections": 5,
  "activeStreams": 2,
  "peakActiveStreams": 3,
  "disconnections": 3,
  "messageEventsSent": 20,
  "heartbeatsSent": 10,
  "totalMessages": 10,
  "clients": [
    { "clientId": "0aaba389", "connectedAt": "13:07:01", "lastEventId": 7 }
  ]
}
```

---

## ⚙️ 주요 설정 (`application.yaml`)

```yaml
server:
  port: 8080

spring:
  mvc:
    async:
      request-timeout: -1   # SSE 연결 유지를 위해 타임아웃 비활성화
                            # 기본값 30초면 SSE 연결이 자동으로 끊김!
```

> **왜 `request-timeout: -1`?**  
> Spring MVC의 기본 async 요청 타임아웃은 30초입니다.  
> SSE는 수 분~수 시간 연결을 유지해야 하므로 무제한으로 설정합니다.

---

## 🛠️ 기술 스택

| 항목 | 버전 |
|------|------|
| Java | 17 |
| Spring Boot | 3.5.13 |
| Spring MVC | (Boot 내장) |
| Lombok | (최신) |
| Gradle | 8.14.4 |

---

## 📝 SSE의 한계 (→ 4단계 WebSocket으로 해결)

1. **단방향**: 서버 → 클라이언트만 가능
   - 클라이언트 → 서버는 별도 `HTTP POST` 필요
   - WebSocket은 단일 연결로 양방향 가능

2. **HTTP/1.1 연결 수 제한**: 브라우저당 같은 도메인에 최대 6개
   - HTTP/2에서는 스트림 다중화로 해결
