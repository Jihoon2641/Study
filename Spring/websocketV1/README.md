# Step 1 — Short Polling Chat

> **실시간 채팅 구현 방식 비교 학습 프로젝트**  
> 이 모듈은 **Short Polling** 방식으로 채팅을 구현하고, 그 한계를 시각적으로 확인하기 위한 Step 1입니다.

---

## 📌 프로젝트 목적

| 단계 | 방식 | 핵심 특징 |
|------|------|-----------|
| **Step 1 (현재)** | Short Polling | 클라이언트가 주기적으로 서버에 요청 |
| Step 2 (예정) | Long Polling | 서버가 새 메시지 생길 때까지 응답 보류 |
| Step 3 (예정) | WebSocket | 서버-클라이언트 영구 연결 유지 |

---

## 🏗️ 프로젝트 구조

```
websocketV1/
├── src/main/java/com/study/websocketV1/
│   ├── WebsocketV1Application.java       # Spring Boot 진입점
│   ├── controller/
│   │   └── Chatcontroller.java           # REST API 엔드포인트
│   ├── service/
│   │   └── ChatService.java              # 비즈니스 로직, 메시지 저장소
│   ├── dto/
│   │   └── ChatMessage.java              # 메시지 데이터 모델
│   └── actuator/
│       └── PollingStats.java             # 폴링 통계 (낭비율 계산)
└── src/main/resources/
    ├── application.yaml                  # 서버 설정
    └── static/
        └── index.html                    # 채팅 UI + 실시간 모니터링 대시보드
```

---

## 🔄 Short Polling 동작 원리

```
[클라이언트]                        [서버]
     |                                |
     |── GET /api/messages/poll ─────>|  (1초마다 반복)
     |                                |── messageStore 조회
     |<── 200 OK { messages: [] } ────|  ← 새 메시지 없음 (낭비!)
     |                                |
     |── GET /api/messages/poll ─────>|
     |<── 200 OK { messages: [...] } ─|  ← 새 메시지 있음 (유의미)
     |                                |
```

> **핵심 문제**: 채팅이 조용한 시간에도 HTTP 요청이 계속 발생 → **낭비율이 매우 높음**

---

## 🌐 API 엔드포인트

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `GET` | `/api/messages/poll?lastId={id}` | **Short Polling 핵심** — 새 메시지 조회 + 통계 반환 |
| `POST` | `/api/messages` | 메시지 전송 |
| `GET` | `/api/messages` | 전체 메시지 조회 (초기 로드) |
| `GET` | `/api/stats` | 폴링 통계만 조회 |
| `DELETE` | `/api/messages` | 메시지 초기화 (테스트용) |

### Poll 응답 예시
```json
{
  "messages": [
    { "id": 3, "sender": "User1", "content": "안녕!", "timestamp": "20:30:15" }
  ],
  "hasNew": true,
  "stats": {
    "totalRequests": 100,
    "emptyResponses": 97,
    "messagesDelivered": 3,
    "wasteRatio": "97.0"
  }
}
```

---

## 🧵 왜 AtomicLong과 CopyOnWriteArrayList인가?

Spring Boot는 요청마다 별도 스레드를 할당합니다.  
`@Service`는 싱글톤이므로 모든 스레드가 같은 `ChatService` 객체를 공유합니다.

```
클라이언트 A (폴링)     → Thread-1 ─┐
클라이언트 B (폴링)     → Thread-2 ─┤── ChatService 공유 (싱글톤)
클라이언트 C (메시지 전송) → Thread-3 ─┘
```

| 필드 | 타입 | 선택 이유 |
|------|------|-----------|
| `messageStore` | `CopyOnWriteArrayList` | 읽기(폴링)가 매우 빈번 → 락 없이 읽기 가능. 쓰기 시에만 배열 복사 |
| `idGenerator` | `AtomicLong` | 메시지 ID 중복 방지. `incrementAndGet()`으로 원자적 증가 보장 |
| `totalRequests` 등 | `AtomicLong` | 여러 스레드가 동시에 카운터 증가 시 데이터 손실 방지 |

> **일반 `long`이나 `ArrayList`를 쓰면?**  
> → `ConcurrentModificationException` 또는 중복 ID 발급 등 Race Condition 발생

---

## ⚙️ 기술 스택

| 항목 | 내용 |
|------|------|
| Java | 17 |
| Spring Boot | 3.5.12 |
| Spring Web | REST API |
| Spring Actuator | `/health`, `/metrics`, `/threaddump` 모니터링 |
| Lombok | 보일러플레이트 코드 제거 |
| 빌드 도구 | Gradle |

---

## 🚀 실행 방법

```bash
# 프로젝트 루트에서
./gradlew bootRun
```

서버 실행 후 브라우저에서 접속:

```
http://localhost:8080
```

---

## 📊 모니터링

### Actuator 엔드포인트
```
GET http://localhost:8080/actuator/health      # 서버 상태
GET http://localhost:8080/actuator/metrics     # 성능 지표
GET http://localhost:8080/actuator/threaddump  # 스레드 상태
```

### UI 대시보드 (`index.html`)
- **실시간 HTTP 요청 로그** — 빈 응답 / 유의미 응답 구분
- **낭비율 바** — Short Polling의 핵심 문제를 시각화
- **요청 타임라인** — 최근 60개 요청을 타일로 표시
- **폴링 간격 조절** — 0.5초 ~ 5초 설정 가능

---
