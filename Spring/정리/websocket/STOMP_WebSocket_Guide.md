# 5단계 STOMP over WebSocket 상세 정리

## 목차

1. [STOMP란 무엇인가](#1-stomp란-무엇인가)
2. [STOMP 프레임 구조](#2-stomp-프레임-구조)
3. [동작 흐름](#3-동작-흐름)
4. [핵심 객체 상세 설명](#4-핵심-객체-상세-설명)
5. [코드 흐름 분석](#5-코드-흐름-분석)
6. [Destination 패턴](#6-destination-패턴)
7. [SockJS — 폴백 전략](#7-sockjs--폴백-전략)
8. [4단계 Raw WebSocket과의 차이점](#8-4단계-raw-websocket과의-차이점)
9. [문제점 및 한계](#9-문제점-및-한계)

---

## 1. STOMP란 무엇인가

**STOMP (Simple Text Oriented Messaging Protocol)**는 메시지 브로커와 통신하기 위한 텍스트 기반 프로토콜이다. WebSocket 위에서 동작하며, Raw WebSocket이 "채널만 제공"한다면 STOMP는 "메시지 라우팅 규약"을 추가한다.

```
[Raw WebSocket]
WebSocket 연결 = 텍스트/바이너리 전송 채널만 제공
→ "이 메시지가 무엇인지"는 개발자가 직접 설계해야 함

[STOMP over WebSocket]
WebSocket 연결 위에 STOMP 프로토콜 계층 추가
→ COMMAND(동사) + destination(목적지) + headers + body로 표준화
→ 누구에게 보낼지, 어떤 채널인지가 프레임에 내장됨
```

### 핵심 개념 — Publish-Subscribe 모델

STOMP는 발행-구독(Pub/Sub) 모델을 채택한다.

```
[발행 - SEND]
클라이언트 ──SEND /app/chat/general──▶ 서버(@MessageMapping)

[구독 - SUBSCRIBE]
클라이언트A ──SUBSCRIBE /topic/chat/general──▶ 브로커
클라이언트B ──SUBSCRIBE /topic/chat/general──▶ 브로커

[배포 - MESSAGE]
브로커 ──MESSAGE /topic/chat/general──▶ 클라이언트A
브로커 ──MESSAGE /topic/chat/general──▶ 클라이언트B
```

---

## 2. STOMP 프레임 구조

STOMP 프레임은 텍스트로 구성되며 세 부분으로 이루어진다.

```
COMMAND\n
header1:value1\n
header2:value2\n
\n                ← 빈 줄이 헤더와 바디를 구분
Body 내용 (선택적)
^@                ← NULL 바이트(0x00)로 프레임 종료
```

### 주요 COMMAND

**클라이언트 → 서버:**

| COMMAND | 설명 | 주요 헤더 |
|---------|------|-----------|
| `CONNECT` | STOMP 연결 수립 (WebSocket 연결 후 별도로 STOMP 핸드셰이크) | `accept-version`, 커스텀 헤더(sender 등) |
| `SEND` | 특정 destination으로 메시지 전송 | `destination`, `content-type` |
| `SUBSCRIBE` | destination 구독 시작 (= 방 입장) | `destination`, `id` |
| `UNSUBSCRIBE` | 구독 취소 (= 방 퇴장) | `id` |
| `DISCONNECT` | 연결 종료 | |

**서버 → 클라이언트:**

| COMMAND | 설명 |
|---------|------|
| `CONNECTED` | CONNECT에 대한 성공 응답 |
| `MESSAGE` | 구독 중인 destination에 메시지 도착 |
| `ERROR` | 오류 알림 |

### 실제 프레임 예시

```
// 클라이언트 CONNECT
CONNECT
accept-version:1.2
sender:Alice

^@

// 서버 CONNECTED
CONNECTED
version:1.2
heart-beat:10000,10000

^@

// 클라이언트 SUBSCRIBE (= 방 입장)
SUBSCRIBE
destination:/topic/chat/general
id:sub-0

^@

// 클라이언트 SEND (= 채팅 메시지 전송)
SEND
destination:/app/chat/general
content-type:application/json

{"sender":"Alice","content":"안녕하세요"}^@

// 서버 MESSAGE (= 브로드캐스트 수신)
MESSAGE
destination:/topic/chat/general
message-id:abc-1
subscription:sub-0

{"id":1,"sender":"Alice","content":"안녕하세요","timestamp":"12:34:56"}^@
```

---

## 3. 동작 흐름

### 전체 흐름 다이어그램

```
클라이언트                                          서버(STOMP 브로커)
    │                                                │
    │── 1. WebSocket HTTP Upgrade ──────────────────▶│
    │◀─ 101 Switching Protocols ────────────────────│
    │                                                │
    │── 2. STOMP CONNECT {sender:Alice} ────────────▶│ StompChannelInterceptor
    │◀─ STOMP CONNECTED ────────────────────────────│ SessionRegistry.register()
    │                                                │
    │── 3. SUBSCRIBE /user/queue/room-list ─────────▶│
    │◀─ MESSAGE /user/queue/room-list [{...}] ───────│ handleSubscribe() → 방 목록 전송
    │                                                │
    │── 4. SUBSCRIBE /topic/chat/general ───────────▶│ (= 방 입장)
    │◀─ MESSAGE /user/queue/history [{...}] ─────────│ 이력 전송 (나에게만)
    │◀─ MESSAGE /topic/chat/general (JOINED) ────────│ 입장 알림 (방 전체)
    │                                                │
    │── 5. SEND /app/chat/general {content} ─────────▶│
    │                                                │ @MessageMapping("/chat/{roomId}")
    │                                                │ roomRepository.save()
    │◀─ MESSAGE /topic/chat/general {ChatMessage} ───│ convertAndSend() → 구독자 전체
    │                                                │
    │── 6. DISCONNECT ───────────────────────────────▶│
    │                                                │ StompChannelInterceptor
    │                                                │ SessionRegistry.unregister()
```

### WebSocket과 STOMP 두 단계 핸드셰이크

STOMP는 WebSocket 위에서 동작하므로 핸드셰이크가 두 번 일어난다.

```
1단계: WebSocket HTTP Upgrade 핸드셰이크
   HTTP GET /ws/chat + Upgrade: websocket
   ↓
   101 Switching Protocols
   ↓
   WebSocket 연결 수립

2단계: STOMP CONNECT 핸드셰이크
   STOMP CONNECT (커스텀 헤더 포함 가능)
   ↓
   STOMP CONNECTED
   ↓
   STOMP 세션 수립 (브로커가 세션 관리 시작)
```

---

## 4. 핵심 객체 상세 설명

### `SimpMessagingTemplate`

> `org.springframework.messaging.simp.SimpMessagingTemplate`

5단계에서 가장 핵심적인 객체다. STOMP 메시지를 특정 destination으로 전송하는 템플릿이다. Raw WebSocket의 세션 순회 브로드캐스트 전체를 이 객체 하나가 대체한다.

```java
@Autowired
private SimpMessagingTemplate messagingTemplate;
```

#### `convertAndSend(destination, payload)` — 브로드캐스트

```java
// 특정 destination의 모든 구독자에게 전송
messagingTemplate.convertAndSend("/topic/chat/general", chatMessage);

// 동작:
// 1. chatMessage 객체를 JSON으로 직렬화
// 2. /topic/chat/general 을 구독 중인 모든 세션 조회 (브로커가 처리)
// 3. 각 세션에 MESSAGE 프레임 전송
```

4단계에서 이와 동일한 동작을 하려면:
```java
for (String sessionId : room.getSessionIds()) {
    ChatSession target = sessions.get(sessionId);
    String json = objectMapper.writeValueAsString(message);
    target.getSession().sendMessage(new TextMessage(json));
}
// → 이 모든 코드가 위의 한 줄로 대체됨
```

#### `convertAndSendToUser(user, destination, payload, headers)` — 특정 사용자에게만

```java
// sessionId 기반으로 특정 사용자에게만 전송
messagingTemplate.convertAndSendToUser(
        sessionId,
        "/queue/room-list",              // 실제 전달 destination: /user/{sessionId}/queue/room-list
        roomRepository.getRoomList(),
        Map.of(SimpMessageHeaderAccessor.SESSION_ID_HEADER, sessionId)
);
```

> **주의:** `convertAndSendToUser`를 sessionId 기반으로 사용할 때는 반드시 `SESSION_ID_HEADER`를 헤더에 명시해야 한다. 명시하지 않으면 `Principal`(인증된 사용자명) 기반으로 라우팅을 시도해 전달되지 않는다.

---

### `@MessageMapping`

> `org.springframework.messaging.handler.annotation.MessageMapping`

클라이언트가 `SEND` 프레임을 보낸 destination에 따라 메서드를 자동으로 라우팅하는 어노테이션이다. Spring MVC의 `@RequestMapping`에 대응하는 개념이다.

```java
// 클라이언트가 SEND /app/chat/general 을 보내면 이 메서드 호출
// /app 접두사는 setApplicationDestinationPrefixes("/app") 설정값
@MessageMapping("/chat/{roomId}")
public void handleChat(
        @DestinationVariable String roomId,  // 경로 변수
        @Payload SendMessageRequest req,     // body 자동 역직렬화
        SimpMessageHeaderAccessor accessor   // STOMP 헤더 접근
) { }
```

4단계의 `switch(type)` 분기와 비교:

```java
// 4단계: 개발자가 직접 파싱 + 분기
String type = objectMapper.readTree(message.getPayload()).path("type").asText();
switch (type) {
    case "JOIN"  -> chatService.joinRoom(...);
    case "CHAT"  -> chatService.handleChat(...);
    case "LEAVE" -> chatService.leaveRoom(...);
    // 기능 추가 시 이 switch에 계속 추가해야 함
}

// 5단계: destination이 타입 역할 → 어노테이션이 자동 라우팅
@MessageMapping("/chat/{roomId}")     // SEND /app/chat/{roomId}
@MessageMapping("/room/{roomId}/join") // 추가 기능도 메서드 하나 추가면 끝
```

---

### `@DestinationVariable`

> `org.springframework.messaging.handler.annotation.DestinationVariable`

`@MessageMapping`의 destination 경로에서 `{변수}` 부분을 자동으로 추출하는 어노테이션이다. Spring MVC의 `@PathVariable`에 대응한다.

```java
// destination: /app/chat/general
// {roomId} → "general"
@MessageMapping("/chat/{roomId}")
public void handleChat(@DestinationVariable String roomId, ...) { }
```

Raw WebSocket에서는 이 추출을 직접 했다.
```java
// 4단계: payload에서 직접 꺼냄
String roomId = payload.path("roomId").asText();
```

---

### `@Payload`

> `org.springframework.messaging.handler.annotation.Payload`

STOMP 프레임의 body(본문)를 자동으로 역직렬화해 파라미터에 바인딩하는 어노테이션이다. Spring MVC의 `@RequestBody`에 대응한다.

```java
@MessageMapping("/chat/{roomId}")
public void handleChat(@Payload SendMessageRequest req, ...) {
    // req는 {"sender":"Alice","content":"안녕"} 을 자동으로 역직렬화한 객체
    String sender = req.getSender();
    String content = req.getContent();
}
```

4단계에서는:
```java
// 직접 역직렬화
JsonNode payload = objectMapper.readTree(message.getPayload()).path("payload");
String content = payload.path("content").asText();
```

---

### `ChannelInterceptor`

> `org.springframework.messaging.support.ChannelInterceptor`

STOMP 메시지가 채널을 통과할 때 가로채는 인터셉터 인터페이스다. Raw WebSocket의 `afterConnectionEstablished()` / `afterConnectionClosed()`를 STOMP 환경에서 대체한다.

```java
public interface ChannelInterceptor {
    // 메시지가 채널에 전달되기 직전
    default Message<?> preSend(Message<?> message, MessageChannel channel) { }

    // 메시지가 채널에 전달된 직후
    default void postSend(Message<?> message, MessageChannel channel, boolean sent) { }

    // 전송 완료 후 (성공/실패 모두)
    default void afterSendCompletion(Message<?> message, MessageChannel channel,
                                      boolean sent, Exception ex) { }
}
```

`preSend()`에서 `null`을 반환하면 해당 프레임이 차단된다. 인증 처리에 활용할 수 있다.

```java
@Override
public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

    if (StompCommand.CONNECT.equals(accessor.getCommand())) {
        String token = accessor.getFirstNativeHeader("Authorization");
        if (!isValidToken(token)) {
            return null; // null 반환 = 프레임 차단 (인증 실패)
        }
    }
    return message; // 그대로 통과
}
```

---

### `StompHeaderAccessor`

> `org.springframework.messaging.simp.stomp.StompHeaderAccessor`

STOMP 프레임의 헤더에 접근하는 편의 클래스다. `ChannelInterceptor`와 `@EventListener` 핸들러에서 사용한다.

```java
StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
// 또는
StompHeaderAccessor accessor =
    MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

// 헤더 읽기
StompCommand command      = accessor.getCommand();       // CONNECT, SEND, SUBSCRIBE 등
String sessionId          = accessor.getSessionId();     // WebSocket 세션 ID
String destination        = accessor.getDestination();   // /topic/chat/general
String subscriptionId     = accessor.getSubscriptionId();// sub-0
String nativeHeader       = accessor.getFirstNativeHeader("sender"); // 커스텀 헤더
```

`StompCommand` 열거형:

| COMMAND | 설명 |
|---------|------|
| `CONNECT` | 클라이언트가 STOMP 연결 요청 |
| `CONNECTED` | 서버가 STOMP 연결 수락 |
| `SEND` | 클라이언트가 메시지 전송 |
| `SUBSCRIBE` | 클라이언트가 destination 구독 |
| `UNSUBSCRIBE` | 클라이언트가 구독 취소 |
| `MESSAGE` | 서버가 구독자에게 메시지 전달 |
| `DISCONNECT` | 클라이언트가 연결 종료 |
| `ERROR` | 오류 프레임 |

---

### `@EventListener` + STOMP 이벤트

> `org.springframework.context.event.EventListener`

Spring Application Event를 처리하는 어노테이션이다. STOMP에서는 연결/구독/종료 이벤트를 감지하는 데 사용한다.

```java
// WebSocket + STOMP 연결 완료
@EventListener
public void handleConnect(SessionConnectedEvent event) { }

// 특정 destination 구독
@EventListener
public void handleSubscribe(SessionSubscribeEvent event) { }

// 연결 종료
@EventListener
public void handleDisconnect(SessionDisconnectEvent event) { }
```

4단계의 `TextWebSocketHandler` 콜백과 비교:

| 4단계 (Raw WebSocket 핸들러) | 5단계 (Spring 이벤트) |
|-----------------------------|----------------------|
| `afterConnectionEstablished()` | `@EventListener SessionConnectedEvent` |
| `handleTextMessage()` | `@MessageMapping` 메서드 |
| `afterConnectionClosed()` | `@EventListener SessionDisconnectEvent` |
| `handlePongMessage()` | 브로커 Heartbeat가 자동 처리 |
| `handleTransportError()` | 브로커가 자동 처리 |

---

### `WebSocketMessageBrokerConfigurer`

> `org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer`

STOMP 메시지 브로커 전체를 설정하는 인터페이스다. 4단계의 `WebSocketConfigurer`보다 훨씬 많은 것을 담당한다.

```java
public interface WebSocketMessageBrokerConfigurer {
    // STOMP 엔드포인트 등록
    void registerStompEndpoints(StompEndpointRegistry registry);

    // 메시지 브로커 설정 (가장 핵심)
    void configureMessageBroker(MessageBrokerRegistry registry);

    // 인바운드 채널 설정 (인터셉터 등록)
    void configureClientInboundChannel(ChannelRegistration registration);

    // 아웃바운드 채널 설정
    void configureClientOutboundChannel(ChannelRegistration registration);
    // ... 기타 메서드들
}
```

```java
@Override
public void configureMessageBroker(MessageBrokerRegistry registry) {
    // 인메모리 브로커 활성화
    // /topic: 1:N 브로드캐스트 (채팅방)
    // /queue: 1:1 (개인 메시지)
    registry.enableSimpleBroker("/topic", "/queue")
            .setHeartbeatValue(new long[]{10000, 10000})  // Heartbeat 주기
            .setTaskScheduler(heartbeatTaskScheduler());   // 스케줄러 필수

    // /app/** 으로 오는 SEND → @MessageMapping 으로 라우팅
    registry.setApplicationDestinationPrefixes("/app");

    // /user/** : convertAndSendToUser 에서 사용하는 prefix
    registry.setUserDestinationPrefix("/user");
}
```

---

### `SimpMessageHeaderAccessor`

> `org.springframework.messaging.simp.SimpMessageHeaderAccessor`

STOMP/SockJS 메시지의 헤더에 접근하는 Spring Messaging 레벨 클래스다. `StompHeaderAccessor`보다 상위 레벨이며 `@MessageMapping` 메서드에서 주로 사용한다.

```java
@MessageMapping("/chat/{roomId}")
public void handleChat(@Payload SendMessageRequest req,
                       SimpMessageHeaderAccessor accessor) {
    String sessionId = accessor.getSessionId();  // WebSocket 세션 ID
    String user      = accessor.getUser() != null
                       ? accessor.getUser().getName() : null; // 인증 사용자
}
```

`convertAndSendToUser`를 sessionId 기반으로 쓸 때 사용하는 헤더 상수:
```java
// SESSION_ID_HEADER = "simpSessionId"
Map.of(SimpMessageHeaderAccessor.SESSION_ID_HEADER, sessionId)
```

---

### `ThreadPoolTaskScheduler`

> `org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler`

STOMP Heartbeat 전송에 사용하는 Spring 스케줄러 구현체다. `setHeartbeatValue()`를 설정하면 반드시 등록해야 한다.

```java
@Bean
public TaskScheduler heartbeatTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);                            // Heartbeat 전용이므로 1개면 충분
    scheduler.setThreadNamePrefix("stomp-heartbeat-");  // 스레드 이름 (디버깅용)
    scheduler.initialize();                              // 명시적 초기화
    return scheduler;
}
```

`setHeartbeatValue()`를 설정하고 `TaskScheduler`를 등록하지 않으면:
```
java.lang.IllegalArgumentException:
  Heartbeat values configured but no TaskScheduler provided
```

---

## 5. 코드 흐름 분석

### CONNECT → 방 목록 전송 흐름

```
1. 클라이언트가 STOMP CONNECT 프레임 전송
   stompClient.connect({ sender: 'Alice' }, onConnected)

2. StompChannelInterceptor.preSend() 호출 (CONNECT 감지)
   → SessionRegistry.register(sessionId, "Alice")

3. 서버가 CONNECTED 프레임 응답
   → Spring이 SessionConnectedEvent 발행

4. 클라이언트의 onConnected() 콜백 실행
   → stompClient.subscribe('/user/queue/room-list', callback)
     (이 구독이 서버에 SUBSCRIBE 프레임으로 전송됨)

5. StompChannelInterceptor.preSend() 호출 (SUBSCRIBE 감지)
   → SessionRegistry.addSubscription(sessionId, "/user/queue/room-list")
   → Spring이 SessionSubscribeEvent 발행

6. ChatController.handleSubscribe() 호출
   → destination == "/user/queue/room-list" 확인
   → messagingTemplate.convertAndSendToUser(sessionId, "/queue/room-list", roomList)
   → 클라이언트의 /user/queue/room-list 콜백에서 방 목록 수신
```

### SUBSCRIBE /topic/chat/{roomId} → 방 입장 흐름

```
1. 클라이언트: stompClient.subscribe('/topic/chat/general', callback)
   → STOMP SUBSCRIBE 프레임 전송

2. ChatController.handleSubscribe() 호출
   → destination.startsWith("/topic/chat/") 확인
   → roomId = "general" 추출

3. 최근 메시지 이력 전송 (나에게만)
   → messagingTemplate.convertAndSendToUser(sessionId, "/queue/history", recentMessages)

4. 입장 알림 브로드캐스트 (방 전체)
   → messagingTemplate.convertAndSend("/topic/chat/general", SystemMessage("Alice 입장"))
   → /topic/chat/general 구독자 전체에게 전달 (브로커가 처리)
```

### SEND /app/chat/{roomId} → 채팅 흐름

```
1. 클라이언트: stompClient.send('/app/chat/general', {}, JSON.stringify({sender, content}))
   → STOMP SEND 프레임 전송

2. Spring이 destination의 /app 접두사를 보고 @MessageMapping으로 라우팅
   → ChatController.handleChat() 호출
   → @DestinationVariable roomId = "general"
   → @Payload SendMessageRequest req = {sender:"Alice", content:"안녕"}

3. roomRepository.save() → ChatMessage 생성 (ID, timestamp 자동 설정)

4. 브로드캐스트
   → messagingTemplate.convertAndSend("/topic/chat/general", chatMessage)
   → SimpleBroker가 /topic/chat/general 구독자 전체에게 MESSAGE 프레임 전송
```

---

## 6. Destination 패턴

STOMP에서 destination은 메시지의 목적지이자 라우팅 키다.

```
/app/**     클라이언트 SEND → @MessageMapping 메서드로 라우팅 (서버가 처리)
/topic/**   Publish-Subscribe: 구독자 전체에게 브로드캐스트 (1:N)
/queue/**   Point-to-Point: 특정 사용자에게만 (1:1)
/user/**    convertAndSendToUser() 사용 시. /user/{sessionId}/queue/... 로 변환
```

### 이 프로젝트에서 사용한 destination 목록

| Destination | 방향 | 설명 |
|-------------|------|------|
| `/app/chat/{roomId}` | 클라→서버 | 채팅 메시지 전송 |
| `/topic/chat/{roomId}` | 서버→구독자 | 채팅 메시지 브로드캐스트 |
| `/user/queue/room-list` | 서버→특정유저 | 방 목록 (나에게만) |
| `/user/queue/history` | 서버→특정유저 | 메시지 이력 (나에게만) |

---

## 7. SockJS — 폴백 전략

WebSocket을 지원하지 않는 환경(구형 브라우저, 일부 기업 방화벽·프록시)을 위한 폴백 라이브러리다.

```java
// 서버 설정
registry.addEndpoint("/ws/chat")
        .withSockJS(); // SockJS 활성화

// 클라이언트 코드
const socket = new SockJS('http://localhost:8080/ws/chat'); // ws:// 가 아닌 http://
const stomp = Stomp.over(socket);
```

SockJS는 연결 시 다음 순서로 최선의 방식을 자동 선택한다.

```
1순위: WebSocket            (ws://)
2순위: HTTP Streaming       (xhr-streaming)
3순위: HTTP Long Polling    (xhr-polling)
```

4단계 Raw WebSocket은 SockJS를 지원하지 않았다. `new WebSocket('ws://')` 그대로라 WebSocket을 지원하지 않는 환경에서는 동작하지 않는다.

---

## 8. 4단계 Raw WebSocket과의 차이점

### 가장 핵심적인 차이 — 보일러플레이트 제거

4단계에서 개발자가 직접 구현해야 했던 것들이 5단계에서 어떻게 바뀌었는지를 보면 STOMP의 가치가 명확하다.

```
[4단계] WsMessage 봉투 타입 시스템
public static final String TYPE_JOIN      = "JOIN";
public static final String TYPE_CHAT      = "CHAT";
public static final String TYPE_LEAVE     = "LEAVE";
public static final String TYPE_PING      = "PING";
...10개의 상수...

[5단계] destination이 타입 역할 → WsMessage 클래스 자체가 불필요
/app/chat/{roomId}     → CHAT에 해당
SUBSCRIBE /topic/chat/ → JOIN에 해당
DISCONNECT             → LEAVE에 해당
Heartbeat 자동         → PING/PONG에 해당
```

```
[4단계] 메시지 라우팅 (handleTextMessage의 switch)
switch (type) {
    case "JOIN"  → joinRoom()
    case "CHAT"  → handleChat()
    case "LEAVE" → leaveRoom()
    case "PING"  → handlePing()
    default      → sendError()
}

[5단계] 어노테이션이 자동 라우팅
@MessageMapping("/chat/{roomId}")      // CHAT
@EventListener(SessionSubscribeEvent)  // JOIN
@EventListener(SessionDisconnectEvent) // LEAVE
브로커 Heartbeat                       // PING
```

### 제거된 파일·클래스 비교

| 4단계 | 5단계 | 제거 이유 |
|-------|-------|----------|
| `WsMessage.java` | 없음 | STOMP destination이 타입 역할 |
| `ChatWebSocketHandler.java` | 없음 | `@MessageMapping` + `@EventListener`로 대체 |
| `ChatSession.java` | `SessionRegistry` (간소화) | `WebSocketSession` 직접 다루지 않음 |
| `ChatRoom.java` | `ChatRoomRepository` (간소화) | `sessionIds` 집합 불필요 (브로커가 구독 관리) |
| `WebSocketStats.java` | 없음 | 저수준 통계 불필요 |

### 방 입장 코드 비교

```java
// [4단계] joinRoom() — 직접 구현한 30줄 메서드
public void joinRoom(ChatSession chatSession, String roomId, String sender) {
    // 1. 이전 방 퇴장
    if (chatSession.isInRoom()) leaveRoom(chatSession);
    // 2. 세션에 정보 설정
    chatSession.setSender(sender);
    chatSession.setRoomId(roomId);
    // 3. 방 멤버 집합에 등록
    room.addSession(chatSession.getSessionId());
    // 4. JOINED 이벤트 전송
    send(chatSession, WsMessage.of(TYPE_JOINED, Map.of("recentMessages", ...)));
    // 5. 입장 알림 브로드캐스트
    broadcastToRoom(roomId, WsMessage.of(TYPE_SYSTEM, "입장"));
}

// [5단계] 구독 이벤트만 감지
@EventListener
public void handleSubscribe(SessionSubscribeEvent event) {
    if (!destination.startsWith("/topic/chat/")) return;
    // 이력 전송
    messagingTemplate.convertAndSendToUser(sessionId, "/queue/history", recent, headers);
    // 입장 알림
    messagingTemplate.convertAndSend("/topic/chat/" + roomId, new SystemMessage(...));
    // 브로커가 구독자 목록 자동 관리 → sessionIds 집합 불필요
}
```

### 브로드캐스트 코드 비교

```java
// [4단계] broadcastToRoom() — 직접 구현
public void broadcastToRoom(String roomId, WsMessage message) {
    ChatRoom room = rooms.get(roomId);
    List<String> deadSessions = new ArrayList<>();
    for (String sessionId : room.getSessionIds()) {        // 세션 직접 순회
        ChatSession target = sessions.get(sessionId);
        if (target == null || !target.isOpen()) {          // 죽은 세션 감지
            deadSessions.add(sessionId); continue;
        }
        String json = objectMapper.writeValueAsString(message); // 직접 직렬화
        target.getSession().sendMessage(new TextMessage(json)); // 직접 전송
    }
    deadSessions.forEach(id -> removeClient(id));          // 직접 정리
}

// [5단계] 한 줄
messagingTemplate.convertAndSend("/topic/chat/" + roomId, message);
// 직렬화, 구독자 조회, 전송, 죽은 세션 정리 → SimpleBroker가 모두 처리
```

### Heartbeat / Dead Connection 비교

```java
// [4단계] 직접 구현 (약 30줄)
@Scheduled(fixedDelay = 15000)
public void detectDeadConnections() {
    long threshold = System.currentTimeMillis() - 30_000;
    sessions.values().stream()
        .filter(cs -> cs.getLastPingTime() < threshold)
        .forEach(cs -> {
            cs.getSession().close();
            removeSession(cs.getSessionId());
        });
}

// [5단계] 설정 두 줄
registry.enableSimpleBroker("/topic", "/queue")
        .setHeartbeatValue(new long[]{10000, 10000})
        .setTaskScheduler(heartbeatTaskScheduler());
```

### 전체 비교표

| 항목 | 4단계 Raw WebSocket | 5단계 STOMP |
|------|---------------------|-------------|
| 메시지 타입 구분 | `type` 필드 + `switch` 직접 설계 | STOMP `destination` 표준 |
| 메시지 라우팅 | `switch(type)` 직접 분기 | `@MessageMapping` 자동 라우팅 |
| 방 멤버 추적 | `ChatRoom.sessionIds` 직접 관리 | STOMP 브로커 자동 관리 |
| 브로드캐스트 | 세션 순회 직접 전송 (30줄) | `convertAndSend()` 한 줄 |
| 1:1 메시지 | 직접 구현 | `convertAndSendToUser()` |
| Dead Connection | `@Scheduled` + `lastPingTime` | `setHeartbeatValue()` 설정 |
| 직렬화 | `ObjectMapper` 직접 호출 | Spring 자동 처리 |
| 연결 이벤트 처리 | `TextWebSocketHandler` 콜백 | `@EventListener` |
| SockJS 폴백 | 없음 | `withSockJS()` |
| 핵심 Spring 객체 | `WebSocketSession`, `TextWebSocketHandler` | `SimpMessagingTemplate`, `@MessageMapping` |
| 클라이언트 API | `new WebSocket('ws://')` | `SockJS` + `Stomp.over()` |

---

## 9. 문제점 및 한계

### 1) SimpleBroker — 단일 서버 한계

이 프로젝트에서 사용한 `enableSimpleBroker()`는 **인메모리 브로커**다. 구독자 목록이 서버 메모리에 저장된다.

```
[2대 서버 환경]
Client A → 서버 1에 연결 + /topic/chat/general 구독
Client B → 서버 2에서 /app/chat/general 로 메시지 SEND

→ 서버 2의 SimpleBroker는 서버 1의 구독자를 모름
→ Client A는 메시지를 받지 못함
```

해결책은 외부 브로커(RabbitMQ, ActiveMQ)로 교체하는 것이다. Spring은 `enableStompBrokerRelay()`로 외부 브로커 연동을 지원한다.

```java
// SimpleBroker 대신 RabbitMQ 연동 (다중 서버 환경)
registry.enableStompBrokerRelay("/topic", "/queue")
        .setRelayHost("rabbitmq-host")
        .setRelayPort(61613);
```

### 2) STOMP 오버헤드

STOMP 프레임은 텍스트 기반이므로 Raw WebSocket 이진 프레임보다 크다. 초당 수천 건의 메시지가 오가는 고성능 환경에서는 이 오버헤드가 문제가 될 수 있다.

### 3) 인증 처리의 복잡성

`ChannelInterceptor`에서 인증 토큰을 검증하는 것은 가능하지만, Spring Security와의 통합이 HTTP 기반보다 복잡하다. STOMP CONNECT 헤더에서 JWT를 추출하고 `Principal`을 설정하는 과정을 직접 구현해야 한다.

### 4) STOMP 자체의 학습 비용

Raw WebSocket보다 추상화 레벨이 높아 편리하지만, `destination` 패턴, `SimpleBroker`와 `StompBrokerRelay`의 차이, `convertAndSendToUser`의 동작 원리, `ChannelInterceptor`의 실행 순서 등 이해해야 할 개념이 더 많다.