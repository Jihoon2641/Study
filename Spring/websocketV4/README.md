# Step 4 — Raw WebSocket Chat

> **실시간 통신 학습 시리즈 4단계**
> Polling → Long Polling → SSE → **Raw WebSocket** → STOMP

이전 단계(SSE)에서는 서버 → 클라이언트 **단방향** 스트리밍을 구현했다.
이번 단계에서는 `WebSocket`을 사용해 **양방향** 실시간 채팅을 직접 구현하며,
Raw WebSocket의 구조와 한계를 체험한다.

---

## 목표

- WebSocket 핸드셰이크(HTTP Upgrade) 흐름 이해
- `TextWebSocketHandler`를 통한 연결/메시지/종료 이벤트 처리
- 직접 설계한 JSON 프로토콜(`type + payload`)로 메시지 라우팅
- 세션 관리, 방(Room) 관리, 브로드캐스트를 수동으로 구현
- Ping/Pong을 통한 Dead Connection 감지
- SSE와의 구조적 차이를 코드로 직접 비교

---

## 기술 스택

| 항목 | 내용 |
|---|---|
| Framework | Spring Boot 4.0.5 |
| Java | 17 |
| WebSocket | `spring-boot-starter-websocket` |
| 직렬화 | Jackson (`jackson-databind`) |
| 모니터링 | Spring Actuator + 커스텀 통계 |
| 빌드 | Gradle |
| 프론트엔드 | Vanilla HTML/CSS/JS (정적 파일) |

---

## 실행 방법

```bash
./gradlew bootRun
```

브라우저에서 `http://localhost:8080` 접속

---

## 프로젝트 구조

```
websocketV4/
└── src/main/java/com/study/websocketV4/
    ├── WebsocketV4Application.java       # 진입점
    │
    ├── config/
    │   └── WebsocketConfig.java          # WebSocket 엔드포인트 등록
    │
    ├── handler/
    │   └── ChatWebSocketHandler.java     # WebSocket 이벤트 처리 (진입점)
    │
    ├── service/
    │   └── ChatService.java              # 비즈니스 로직 (세션/방/메시지)
    │
    ├── session/
    │   └── ChatSession.java              # WebSocketSession 래퍼 (비즈니스 상태)
    │
    ├── model/
    │   ├── WsMessage.java                # 공통 메시지 봉투 (type + payload)
    │   ├── ChatMessage.java              # 채팅 메시지 모델
    │   └── ChatRoom.java                 # 채팅방 모델
    │
    ├── actuator/
    │   └── WebSocketStats.java           # 연결 통계 (AtomicLong)
    │
    └── controller/
        └── StatsController.java          # GET /stats (대시보드용 REST API)

src/main/resources/static/
    └── index.html                        # 채팅 UI + 모니터링 대시보드
```

---

## 핵심 개념

### WebSocket vs SSE

| 항목 | SSE | Raw WebSocket |
|---|---|---|
| 방향 | 서버 → 클라이언트 (단방향) | 양방향 |
| 프로토콜 | HTTP (`text/event-stream`) | `ws://` (Upgrade) |
| 클라이언트 전송 | 별도 HTTP POST 필요 | 같은 연결에서 바로 전송 |
| 메시지 구분 | `event:` 필드 (표준) | 직접 설계해야 함 |
| 연결 객체 | `SseEmitter` | `WebSocketSession` |

### WebSocket 핸드셰이크 흐름

```
클라이언트                              서버
    |                                    |
    |──── HTTP GET /ws/chat ────────────>|
    |     (Upgrade: websocket)           |
    |                                    |
    |<─── 101 Switching Protocols ───────|
    |                                    |
    |<══════ WebSocket 프레임 통신 ═══════>|
```

### 메시지 프로토콜 (직접 설계)

Raw WebSocket은 단순한 텍스트/바이너리 프레임만 지원하므로, 메시지 종류를 구분하기 위한 프로토콜을 직접 설계해야 한다.

```json
// 공통 봉투 형식
{ "type": "CHAT", "payload": { ... } }
```

| 방향 | type | payload |
|---|---|---|
| 클 → 서 | `JOIN` | `{ roomId, sender }` |
| 클 → 서 | `CHAT` | `{ content }` |
| 클 → 서 | `LEAVE` | `{}` |
| 클 → 서 | `PING` | `{}` |
| 서 → 클 | `JOINED` | `{ roomId, roomName, memberCount, recentMessages }` |
| 서 → 클 | `CHAT` | `{ id, sender, content, timestamp }` |
| 서 → 클 | `SYSTEM` | `{ text, memberCount }` |
| 서 → 클 | `ROOM_LIST` | `[ { roomId, name, memberCount } ]` |
| 서 → 클 | `PONG` | `{ time }` |
| 서 → 클 | `ERROR` | `{ message }` |

### 세션 관리 구조

```
ChatService
├── sessions: Map<sessionId, ChatSession>   ← 전체 세션
└── rooms:    Map<roomId, ChatRoom>
              └── ChatRoom.sessionIds: Set<String>  ← 방별 세션 ID
```

`WebSocketSession`(Spring 제공) 위에 `ChatSession`(직접 구현)을 래퍼로 씌워 닉네임, 방 정보, Ping 시각 등 비즈니스 상태를 관리한다.

### Dead Connection 감지

```java
@Scheduled(fixedDelay = 15000)   // 15초마다 실행
public void detectDeadConnections() {
    // 마지막 Ping으로부터 30초 이상 지난 세션 강제 종료
}
```

클라이언트는 10초마다 `PING`을 전송하고, 서버는 15초마다 확인한다.

---

## API

### WebSocket

| 엔드포인트 | 설명 |
|---|---|
| `ws://localhost:8080/ws/chat` | WebSocket 연결 |

### REST (모니터링)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/stats` | 활성 연결 수, 프레임 수, 세션 목록, 방 목록 등 통계 |
| GET | `/actuator` | Spring Actuator 메타 정보 |

---

## Raw WebSocket의 한계 (다음 단계 STOMP에서 해결)

이 프로젝트를 구현하면서 직접 만들어야 했던 보일러플레이트들:

| 문제 | Raw WebSocket | STOMP |
|---|---|---|
| 메시지 구분 | `type` 필드를 직접 설계 | `COMMAND` 표준화 |
| 방(Room) 관리 | `sessionId → roomId` 맵핑 직접 구현 | `SUBSCRIBE /topic/room-1` |
| 브로드캐스트 | 세션 목록 순회 후 직접 전송 | `convertAndSend()` 한 줄 |
| Dead Connection | 스케줄러로 직접 감지 및 제거 | 프레임워크가 처리 |

---

## 학습 포인트

1. **`TextWebSocketHandler`** - WebSocket 이벤트(연결/메시지/종료/오류)를 처리하는 진입점
2. **`WebSocketSession`** - 연결 하나 = 세션 하나. 탭마다 별도 세션 생성
3. **이중 세션 관리** - `sessions` Map(전체)과 `ChatRoom.sessionIds` Set(방별)을 별도로 유지
4. **`@EnableWebSocket` + `WebSocketConfigurer`** - WebSocket 핸들러를 URL에 등록하는 설정
5. **Ping/Pong 2계층** - WebSocket 프로토콜 레벨(`PongMessage`) vs 애플리케이션 레벨(`TYPE_PING`)
