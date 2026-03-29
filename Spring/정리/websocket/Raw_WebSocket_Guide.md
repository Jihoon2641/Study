# 4단계 Raw WebSocket 상세 정리

## 목차

1. [WebSocket이란 무엇인가](#1-websocket이란-무엇인가)
2. [HTTP Upgrade 핸드셰이크](#2-http-upgrade-핸드셰이크)
3. [동작 흐름](#3-동작-흐름)
4. [WebSocket 프레임 구조](#4-websocket-프레임-구조)
5. [핵심 객체 상세 설명](#5-핵심-객체-상세-설명)
6. [코드 흐름 분석](#6-코드-흐름-분석)
7. [애플리케이션 레벨 Ping/Pong](#7-애플리케이션-레벨-pingpong)
8. [3단계 SSE와의 차이점](#8-3단계-sse와의-차이점)
9. [문제점 및 한계](#9-문제점-및-한계)

---

## 1. WebSocket이란 무엇인가

**WebSocket**은 하나의 TCP 연결 위에서 서버와 클라이언트가 **양방향(Full-Duplex)**으로 자유롭게 메시지를 주고받는 프로토콜이다. RFC 6455에 정의되어 있다.

```
[SSE — 단방향]
Client ──────────────────────────────────── (송신 불가)
       ◀── 이벤트 ◀── 이벤트 ◀── 이벤트 ── Server

[WebSocket — 양방향]
Client ──── 프레임 ──── 프레임 ────────────▶ Server
Client ◀─── 프레임 ◀─── 프레임 ◀─────────── Server
```

SSE가 서버→클라이언트 단방향 스트리밍이었다면, WebSocket은 **두 방향 모두 언제든 자유롭게 전송**할 수 있다. 클라이언트가 메시지를 보낼 때 더 이상 별도의 HTTP POST 요청이 필요 없다.

### 주요 특성

| 특성 | 설명 |
|------|------|
| Full-Duplex | 서버↔클라이언트 동시 양방향 통신 |
| Persistent Connection | 명시적으로 닫기 전까지 연결 유지 |
| Low Latency | 연결 재수립 없이 프레임 단위로 즉시 전송 |
| 경량 헤더 | HTTP 헤더가 아닌 2~10바이트의 WebSocket 프레임 헤더 사용 |
| 프로토콜 | `ws://` (평문), `wss://` (TLS 암호화) |

---

## 2. HTTP Upgrade 핸드셰이크

WebSocket은 HTTP로 시작해서 WebSocket 프로토콜로 전환된다. 이 전환 과정을 **HTTP Upgrade 핸드셰이크**라고 한다.

```
[1단계] 클라이언트가 HTTP Upgrade 요청
──────────────────────────────────────────
GET /ws/chat HTTP/1.1
Host: localhost:8080
Upgrade: websocket                          ← WebSocket으로 업그레이드 요청
Connection: Upgrade
Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ== ← 랜덤 Base64 키 (보안 검증용)
Sec-WebSocket-Version: 13                   ← WebSocket 프로토콜 버전

[2단계] 서버가 101로 응답 → 프로토콜 전환 완료
──────────────────────────────────────────
HTTP/1.1 101 Switching Protocols
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo= ← 키 검증값 (SHA-1 해시)

[3단계] 이후부터 WebSocket 프레임으로 통신
──────────────────────────────────────────
Client ⇌══ WebSocket Frame ══ Server
```

### Sec-WebSocket-Key 검증 원리

서버는 클라이언트가 보낸 키를 그대로 쓰지 않고, RFC 6455에 정의된 고정 GUID와 합쳐 SHA-1 해시를 만들어 응답한다.

```
클라이언트 키: "dGhlIHNhbXBsZSBub25jZQ=="
고정 GUID:     "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

서버 계산:
1. 두 문자열 연결
2. SHA-1 해시 계산
3. Base64 인코딩
→ "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
```

이 검증은 일반 HTTP 캐시 서버가 WebSocket 요청을 잘못 캐싱하는 것을 방지하기 위한 장치다.

---

## 3. 동작 흐름

### 연결 수립 ~ 채팅 ~ 퇴장 전체 흐름

```
클라이언트                                       서버
    │                                             │
    │── GET /ws/chat (HTTP Upgrade) ─────────────▶│
    │◀─ 101 Switching Protocols ─────────────────│  핸드셰이크 완료
    │                                             │  afterConnectionEstablished()
    │                                             │  ChatSession 등록
    │◀── TextMessage(ROOM_LIST) ─────────────────│  방 목록 전송
    │                                             │
    │── TextMessage(JOIN {roomId, sender}) ───────▶│
    │                                             │  joinRoom() 실행
    │◀── TextMessage(JOINED {recentMessages}) ───│  입장 확인 (나에게만)
    │◀── TextMessage(SYSTEM "입장했습니다") ──────│  입장 알림 (방 전체)
    │                                             │
    │── TextMessage(CHAT {content}) ─────────────▶│
    │                                             │  handleChat() 실행
    │◀── TextMessage(CHAT {ChatMessage}) ─────────│  브로드캐스트 (방 전체)
    │                                             │
    │    [10초 경과]                              │
    │── TextMessage(PING) ───────────────────────▶│
    │◀── TextMessage(PONG) ──────────────────────│  lastPingTime 갱신
    │                                             │
    │    [브라우저 탭 닫기]                        │
    │                                             │  afterConnectionClosed()
    │                                             │  ChatSession 제거
    │                                             │  leaveRoom() 실행
    │                                 TextMessage(SYSTEM "퇴장했습니다")──▶ 방 나머지 멤버들
```

### 클라이언트가 보내는 모든 메시지 포맷

Raw WebSocket은 프레임 안에 무엇을 담을지 표준이 없다. 이 프로젝트에서는 아래와 같은 JSON 봉투 포맷을 직접 설계했다.

```json
// 방 입장
{ "type": "JOIN",  "payload": { "roomId": "general", "sender": "Alice" } }

// 메시지 전송
{ "type": "CHAT",  "payload": { "content": "안녕하세요" } }

// 방 퇴장
{ "type": "LEAVE", "payload": {} }

// 연결 확인
{ "type": "PING",  "payload": {} }
```

---

## 4. WebSocket 프레임 구조

HTTP의 요청/응답 구조와 달리, WebSocket 연결 수립 후의 모든 데이터는 **프레임(Frame)** 단위로 전송된다.

```
Bit:  0       1       2       3
      01234567 01234567 01234567 01234567
      ├──────┬─┬──────────────────────────
      │FIN RSV│M│  Payload Length (7 bit)
      │    OPC│A│
      │    ODE│S│
      │       │K│
      ├───────┴─┴──────────────────────────
      │    Masking Key (4 bytes, if MASK=1)
      ├────────────────────────────────────
      │    Payload Data
      └────────────────────────────────────
```

| 필드 | 설명 |
|------|------|
| `FIN` | 1이면 마지막 프레임(메시지 완료). 큰 데이터는 여러 프레임으로 분할 가능 |
| `Opcode` | 프레임 타입: 0x1=텍스트, 0x2=바이너리, 0x8=연결종료, 0x9=Ping, 0xA=Pong |
| `MASK` | 클라이언트→서버 방향은 반드시 1 (마스킹 필수). 서버→클라이언트는 0 |
| `Payload Length` | 페이로드 크기. 126 또는 127이면 다음 2 또는 8바이트에 실제 길이 |
| `Masking Key` | MASK=1일 때 4바이트 XOR 마스킹 키 |

> 클라이언트 → 서버 방향 프레임은 반드시 마스킹해야 한다. 마스킹은 중간 프록시의 보안 취약점을 방지하기 위한 스펙 요구사항이다.

---

## 5. 핵심 객체 상세 설명

### `WebSocketSession`

> `org.springframework.web.socket.WebSocketSession`

Spring WebSocket에서 **클라이언트와의 연결 하나를 표현하는 인터페이스**다. `afterConnectionEstablished()`의 파라미터로 전달된다.

```java
public interface WebSocketSession {
    String getId();                                        // 세션 고유 ID
    URI getUri();                                          // 연결 URI
    InetSocketAddress getRemoteAddress();                  // 클라이언트 IP/포트
    boolean isOpen();                                      // 연결 열려있는지 확인
    void sendMessage(WebSocketMessage<?> message);         // 메시지 전송
    void close();                                          // 연결 종료
    void close(CloseStatus status);                        // 상태 코드와 함께 종료
    Map<String, Object> getAttributes();                   // 세션에 데이터 저장/조회
}
```

SSE의 `SseEmitter`와 역할이 비슷하지만 차이가 있다.

| 항목 | SseEmitter | WebSocketSession |
|------|-----------|-----------------|
| 방향 | 서버→클라이언트 전용 | 양방향 |
| 전송 | `send(SseEmitter.event()...)` | `sendMessage(new TextMessage(...))` |
| 수신 | 없음 (별도 HTTP POST) | `handleTextMessage()` 콜백 |
| 직렬화 | Spring이 자동 JSON 변환 | 직접 JSON 문자열로 변환 후 전송 |

---

### `TextWebSocketHandler`

> `org.springframework.web.socket.handler.TextWebSocketHandler`

WebSocket 이벤트를 처리하는 추상 클래스다. `WebSocketHandler` 인터페이스의 편의 구현체로, 텍스트 프레임 처리에 특화되어 있다.

```
WebSocketHandler (인터페이스)
    └── AbstractWebSocketHandler (추상 클래스)
            ├── TextWebSocketHandler   ← 텍스트 프레임 처리에 특화
            └── BinaryWebSocketHandler ← 바이너리 프레임 처리에 특화
```

재정의 가능한 메서드들:

```java
public class ChatWebSocketHandler extends TextWebSocketHandler {

    // WebSocket 연결 수립 완료 (HTTP Upgrade 핸드셰이크 완료 직후)
    @Override
    public void afterConnectionEstablished(WebSocketSession session) { }

    // 텍스트 프레임 수신 (클라이언트가 보낸 메시지)
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) { }

    // WebSocket 레벨 Pong 프레임 수신 (우리가 보낸 Ping에 대한 응답)
    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) { }

    // 연결 종료 (정상/비정상 모두 호출됨)
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { }

    // 전송 오류 발생
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) { }
}
```

SSE에는 이런 핸들러 클래스가 없었다. SSE는 서버→클라이언트 단방향이라 클라이언트에서 뭔가 오면 `@PostMapping`으로 별도 처리했다. WebSocket은 양방향이므로 모든 이벤트가 이 핸들러 하나에 집약된다.

---

### `TextMessage`

> `org.springframework.web.socket.TextMessage`

WebSocket 텍스트 프레임을 표현하는 클래스다. `WebSocketSession.sendMessage()`에 전달된다.

```java
// 생성
TextMessage message = new TextMessage("텍스트 내용");
TextMessage message = new TextMessage(jsonString); // JSON 문자열

// 수신 시 내용 꺼내기
String payload = message.getPayload();
int payloadLength = message.getPayloadLength();
boolean isLast = message.isLast(); // 마지막 프레임인지 (큰 메시지 분할 시)
```

Raw WebSocket에서는 모든 직렬화를 직접 처리한다.

```java
// 4단계: 전송 시 직접 직렬화
String json = objectMapper.writeValueAsString(wsMessage);
session.sendMessage(new TextMessage(json));

// 4단계: 수신 시 직접 역직렬화
JsonNode root = objectMapper.readTree(message.getPayload());
String type = root.path("type").asText();
```

5단계 STOMP에서는 이 과정이 전부 사라진다. `@Payload` 어노테이션이 자동으로 역직렬화하고, `convertAndSend()`가 자동으로 직렬화한다.

---

### `CloseStatus`

> `org.springframework.web.socket.CloseStatus`

WebSocket 연결 종료 시 상태 코드와 이유를 담는 클래스다. `afterConnectionClosed()`에서 전달된다.

```java
// 주요 상태 코드 상수
CloseStatus.NORMAL         // 1000: 정상 종료
CloseStatus.GOING_AWAY     // 1001: 브라우저 탭 닫기 / 페이지 이동
CloseStatus.PROTOCOL_ERROR // 1002: 프로토콜 오류
CloseStatus.NOT_ACCEPTABLE // 1003: 수락할 수 없는 데이터 타입
CloseStatus.NO_STATUS_CODE // 1005: 상태 코드 없음
CloseStatus.SESSION_NOT_RELIABLE // 1006: 비정상 종료 (서버 다운 등)
CloseStatus.SERVER_ERROR   // 1011: 서버 내부 오류

// 커스텀 상태 코드
new CloseStatus(4000, "이유 설명");
```

```java
@Override
public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    log.info("연결 종료 code={} reason={}", status.getCode(), status.getReason());
    chatService.removeSession(session.getId());
}
```

---

### `PongMessage`

> `org.springframework.web.socket.PongMessage`

WebSocket 프로토콜 레벨의 Pong 프레임을 표현하는 클래스다.

WebSocket 스펙에는 두 가지 레벨의 Ping/Pong이 있다.

| 레벨 | 누가 처리 | 프레임 | 사용 목적 |
|------|-----------|--------|-----------|
| WebSocket 프로토콜 레벨 | 브라우저/Spring이 자동 | `PingMessage` / `PongMessage` | 연결 상태 확인 (RFC 6455) |
| 애플리케이션 레벨 | 개발자가 직접 구현 | `TextMessage` (type: "PING") | Dead Connection 감지 |

이 프로젝트에서는 두 가지 모두 사용한다.

```java
// WebSocket 프로토콜 레벨 Pong 수신
@Override
protected void handlePongMessage(WebSocketSession session, PongMessage message) {
    // 브라우저가 자동으로 보내는 Pong
    chatSession.setLastPingTime(System.currentTimeMillis());
}

// 애플리케이션 레벨 PING (TextMessage로 직접 구현)
// 클라이언트: ws.send(JSON.stringify({ type: "PING", payload: {} }))
// 서버: handleTextMessage() → switch("PING") → handlePing()
```

---

### `WebSocketConfigurer`

> `org.springframework.web.socket.config.annotation.WebSocketConfigurer`

WebSocket 핸들러를 URL에 등록하는 설정 인터페이스다.

```java
@Configuration
@EnableWebSocket  // WebSocket 기능 활성화
public class WebSocketConfig implements WebSocketConfigurer {

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws/chat")
                .setAllowedOrigins("*");
    }
}
```

SSE는 이런 설정이 없었다. `@GetMapping`과 `produces = MediaType.TEXT_EVENT_STREAM_VALUE`만으로 SSE 엔드포인트를 선언했다. WebSocket은 HTTP 엔드포인트가 아니라 별도의 핸들러 등록 메커니즘을 사용한다.

---

### `ObjectMapper` (WebSocket에서의 역할)

> `com.fasterxml.jackson.databind.ObjectMapper`

Raw WebSocket에서 JSON 직렬화/역직렬화를 전담하는 Jackson 클래스다.

```java
// 수신: 텍스트 프레임 → 객체
JsonNode root    = objectMapper.readTree(message.getPayload());
String type      = root.path("type").asText();
JsonNode payload = root.path("payload");
String roomId    = payload.path("roomId").asText();

// 송신: 객체 → 텍스트 프레임
String json = objectMapper.writeValueAsString(wsMessage);
session.sendMessage(new TextMessage(json));
```

SSE에서는 `emitter.send(someObject)`라고 하면 Spring이 Jackson을 내부적으로 호출해 자동 직렬화했다. Raw WebSocket에서는 이 과정을 직접 작성해야 한다.

---

## 6. 코드 흐름 분석

### 연결 수립 흐름

```java
// 1. WebSocketConfig에서 핸들러 등록
registry.addHandler(chatWebSocketHandler, "/ws/chat");
// → /ws/chat 으로 HTTP Upgrade 요청이 오면 ChatWebSocketHandler로 전달

// 2. 핸드셰이크 완료 후 afterConnectionEstablished 호출
@Override
public void afterConnectionEstablished(WebSocketSession session) {
    // WebSocketSession을 ChatSession 래퍼로 감싸서 저장
    chatService.registerSession(session);

    // 연결 직후 방 목록 전송
    ChatSession chatSession = chatService.getSession(session.getId());
    chatService.sendRoomList(chatSession);
    // → objectMapper로 직렬화 → new TextMessage(json) → session.sendMessage()
}
```

### 메시지 수신 → 분기 흐름

```java
@Override
protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    // 모든 메시지가 이 메서드 하나로 들어온다
    JsonNode root = objectMapper.readTree(message.getPayload());
    String type   = root.path("type").asText();

    // type 필드를 직접 파싱해서 분기
    // → 이것이 Raw WebSocket의 근본 문제: 타입이 늘수록 switch가 커짐
    switch (type) {
        case WsMessage.TYPE_JOIN  -> chatService.joinRoom(...);
        case WsMessage.TYPE_CHAT  -> chatService.handleChat(...);
        case WsMessage.TYPE_LEAVE -> chatService.leaveRoom(...);
        case WsMessage.TYPE_PING  -> chatService.handlePing(...);
        default -> chatService.sendError(...);
    }
}
```

### 방 입장 흐름 (joinRoom의 6단계 수작업)

```java
public void joinRoom(ChatSession chatSession, String roomId, String sender) {
    // 1. 이전 방 퇴장 처리
    if (chatSession.isInRoom() && !chatSession.getRoomId().equals(roomId)) {
        leaveRoom(chatSession);
    }
    // 2. 세션에 닉네임, roomId 설정
    chatSession.setSender(sender);
    chatSession.setRoomId(roomId);
    // 3. 방 멤버 집합에 세션 ID 추가
    room.addSession(chatSession.getSessionId());
    // 4. 나에게만 JOINED 이벤트 전송 (최근 메시지 30개 포함)
    send(chatSession, WsMessage.of(TYPE_JOINED, Map.of("recentMessages", ...)));
    // 5. 방 전체에 입장 알림 브로드캐스트
    broadcastToRoom(roomId, WsMessage.of(TYPE_SYSTEM, Map.of("text", sender + " 입장")));
}
```

5단계 STOMP에서는 이 메서드 자체가 사라지고 `@EventListener`로 대체된다.

---

## 7. 애플리케이션 레벨 Ping/Pong

Raw WebSocket에서 Dead Connection을 감지하기 위해 직접 구현한 Ping/Pong 메커니즘이다.

```
동작 원리:
1. 클라이언트가 10초마다 PING 프레임 전송
2. 서버가 수신 즉시 PONG으로 응답 + lastPingTime 갱신
3. 서버의 @Scheduled 스케줄러가 15초마다 모든 세션을 점검
4. lastPingTime이 30초 이상 경과한 세션 = Dead Connection으로 판단 → 강제 종료
```

```java
// 서버: Ping 수신 처리
public void handlePing(ChatSession chatSession) {
    chatSession.setLastPingTime(System.currentTimeMillis()); // 갱신
    send(chatSession, WsMessage.of(TYPE_PONG, Map.of("time", System.currentTimeMillis())));
}

// 서버: Dead Connection 감지 (15초마다 실행)
@Scheduled(fixedDelay = 15000)
public void detectDeadConnections() {
    long threshold = System.currentTimeMillis() - 30_000; // 30초
    sessions.values().stream()
        .filter(cs -> cs.getLastPingTime() < threshold)
        .forEach(cs -> {
            cs.getSession().close();
            removeSession(cs.getSessionId());
        });
}
```

5단계 STOMP에서는 `setHeartbeatValue(new long[]{10000, 10000})` 설정 한 줄로 이 모든 코드가 대체된다.

---

## 8. 3단계 SSE와의 차이점

### 가장 중요한 차이 — 단방향 vs 양방향

```
[SSE — 3단계]
클라이언트 → 서버 : POST /api/messages (HTTP)
클라이언트 ← 서버 : GET  /api/subscribe (SSE 스트림)
수신·송신이 두 개의 별개 연결

[Raw WebSocket — 4단계]
클라이언트 ⇌ 서버 : ws://localhost:8080/ws/chat
수신·송신 모두 하나의 WebSocket 연결
```

### 설정 방식 차이

```java
// 3단계 SSE: 일반 @GetMapping으로 선언
@GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter subscribe(...) { return new SseEmitter(0L); }

// 4단계 WebSocket: 별도 Config에서 핸들러 등록
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws/chat");
    }
}
```

### 클라이언트 코드 차이

```javascript
// 3단계 SSE
const es = new EventSource('/api/subscribe');     // 수신 전용
es.addEventListener('message', (e) => { ... });
// 송신은 별도 fetch POST

// 4단계 WebSocket
const ws = new WebSocket('ws://localhost:8080/ws/chat');
ws.onmessage = (e) => {                           // 수신
    const msg = JSON.parse(e.data);
    switch(msg.type) { ... }                       // type 직접 분기
};
ws.send(JSON.stringify({ type: 'CHAT', ... }));   // 송신 (같은 연결!)
```

### 서버 수신 처리 차이

```java
// 3단계 SSE: 클라이언트 → 서버는 별도 @PostMapping
@PostMapping("/messages")
public ResponseEntity<ChatMessage> sendMessage(@RequestBody ...) { }

// 4단계 WebSocket: 모든 수신이 한 곳에서
@Override
protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    // 모든 클라이언트 → 서버 메시지가 여기로 들어옴
    String type = objectMapper.readTree(message.getPayload()).path("type").asText();
    switch (type) { ... }
}
```

### 연결 수립 비교

```
[SSE]
1. HTTP GET /api/subscribe
2. 서버가 200 OK + Content-Type: text/event-stream 응답
3. 스트리밍 모드로 전환 (여전히 HTTP)

[WebSocket]
1. HTTP GET /ws/chat + Upgrade: websocket 헤더
2. 서버가 101 Switching Protocols 응답
3. HTTP → WebSocket 프로토콜 완전 전환
4. 이후부터 HTTP가 아닌 WebSocket 프레임으로 통신
```

### 전체 비교표

| 항목 | 3단계 SSE | 4단계 Raw WebSocket |
|------|-----------|---------------------|
| 통신 방향 | 서버→클라이언트 단방향 | 양방향 |
| 클라이언트 송신 | 별도 HTTP POST | 같은 WebSocket 연결 |
| 프로토콜 | HTTP (`text/event-stream`) | WebSocket (`ws://`) |
| 핵심 Spring 객체 | `SseEmitter` | `WebSocketSession`, `TextWebSocketHandler` |
| 클라이언트 API | `EventSource` | `WebSocket` |
| 브라우저 자동 재연결 | ✅ (기본 제공) | ❌ (직접 구현) |
| Last-Event-ID 지원 | ✅ (브라우저 자동) | ❌ (직접 구현) |
| 메시지 라우팅 | HTTP 경로 기반 | type 필드 직접 파싱 + switch |
| 방 관리 | 해당 없음 | `ChatRoom.sessionIds` 직접 관리 |
| Dead Connection 감지 | Heartbeat IOException | `@Scheduled` + `lastPingTime` |
| 직렬화 | Spring 자동 | `ObjectMapper` 직접 호출 |
| HTTP 연결 수 제한 | 도메인당 6개 제한 | 제한 없음 (별도 프로토콜) |

---

## 9. 문제점 및 한계

### 1) 프로토콜 직접 설계 — 가장 큰 부담

Raw WebSocket은 데이터를 주고받는 채널만 제공한다. "이 메시지가 채팅인지, 입장인지, Ping인지"를 구분할 표준이 없다. 따라서 `WsMessage` 같은 봉투 포맷과 `switch(type)` 분기 로직을 직접 설계해야 한다.

```java
// 이 모든 것이 개발자가 직접 유지해야 하는 코드
public static final String TYPE_JOIN      = "JOIN";
public static final String TYPE_CHAT      = "CHAT";
public static final String TYPE_LEAVE     = "LEAVE";
public static final String TYPE_PING      = "PING";
public static final String TYPE_JOINED    = "JOINED";
public static final String TYPE_ROOM_LIST = "ROOM_LIST";
// ... 기능이 추가될 때마다 늘어남
```

### 2) 방 관리 직접 구현

채팅방 개념(`ChatRoom`), 방에 누가 있는지(`sessionIds`), 방 전환 시 처리(`joinRoom` 6단계)를 전부 직접 구현해야 한다.

### 3) 브로드캐스트 직접 구현

```java
// 방의 모든 멤버에게 메시지 전송 — 30줄짜리 메서드
public void broadcastToRoom(String roomId, WsMessage message) {
    for (String sessionId : room.getSessionIds()) {
        ChatSession target = sessions.get(sessionId);
        if (target == null || !target.isOpen()) {
            deadSessions.add(sessionId);
            continue;
        }
        String json = objectMapper.writeValueAsString(message);
        target.getSession().sendMessage(new TextMessage(json));
    }
    deadSessions.forEach(id -> removeClient(id));
}
// 5단계 STOMP에서는 이 전체가 한 줄로 대체된다
```

### 4) Dead Connection 감지 직접 구현

`@Scheduled` 스케줄러 + `lastPingTime` + 클라이언트 Ping 전송을 모두 직접 구현해야 한다. 설정 한 줄(`setHeartbeatValue`)로 해결하는 STOMP와 대조적이다.

### 5) 다중 서버 환경 문제

`sessions` 맵과 `rooms` 맵이 서버 인스턴스 메모리에 있으므로, 로드밸런서 환경에서 서버가 여러 대면 브로드캐스트가 자신에게 연결된 클라이언트에게만 전달된다. SSE와 동일한 문제이며, Redis Pub/Sub 등으로 해결해야 한다.