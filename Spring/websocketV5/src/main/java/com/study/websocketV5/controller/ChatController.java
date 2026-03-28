package com.study.websocketV5.controller;

import java.util.List;
import java.util.Map;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import com.study.websocketV5.model.ChatMessage;
import com.study.websocketV5.model.SendMessageRequest;
import com.study.websocketV5.model.SystemMessage;
import com.study.websocketV5.registry.SessionRegistry;
import com.study.websocketV5.repository.ChatRoomRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * STOMP 메시지 컨트롤러
 *
 * ─────────────────────────────────────────────────────────────────────────
 * [4단계 Raw WebSocket 에서 이 역할을 담당한 코드들]
 *
 * ChatWebSocketHandler.handleTextMessage()
 * → JSON 파싱
 * → switch(type) { case JOIN, CHAT, LEAVE, PING ... }
 * → 각 케이스별로 chatService 메서드 직접 호출
 *
 * ChatService
 * → joinRoom() : 6단계 수작업
 * → broadcastToRoom(): 세션 순회 직접 전송
 * → handlePing() : Ping/Pong 직접 처리
 * → removeSession() : 연결 종료 시 직접 정리
 *
 * [5단계 STOMP 에서 이 모든 것이 아래 어노테이션으로 대체됨]
 *
 * @MessageMapping("/chat/{roomId}") → 라우팅 (switch 분기 대체)
 * @EventListener(SessionSubscribeEvent) → 입장 감지 (afterConnectionEstablished 대체)
 * @EventListener(SessionDisconnectEvent) → 퇴장 감지 (afterConnectionClosed 대체)
 * messagingTemplate.convertAndSend() → 브로드캐스트 (broadcastToRoom 대체)
 * ─────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatController {

    /**
     * Spring STOMP가 제공하는 메시지 전송 템플릿.
     *
     * [4단계] 브로드캐스트:
     * for (String sessionId : room.getSessionIds()) {
     * ChatSession target = sessions.get(sessionId);
     * String json = objectMapper.writeValueAsString(message);
     * target.getSession().sendMessage(new TextMessage(json));
     * }
     * → 세션 순회 + 직렬화 + 전송을 직접 구현
     *
     * [5단계] 브로드캐스트:
     * messagingTemplate.convertAndSend("/topic/chat/room-1", message);
     * → 한 줄로 대체. 직렬화, 구독자 조회, 전송을 프레임워크가 처리
     */
    private final SimpMessagingTemplate messagingTemplate;

    private final ChatRoomRepository roomRepository;
    private final SessionRegistry sessionRegistry;

    // ── 메시지 수신 ──────────────────────────────────────────────────────────

    /**
     * 채팅 메시지 수신 및 브로드캐스트
     *
     * 클라이언트가 STOMP SEND 프레임을 /app/chat/{roomId}로 전송하면 호출된다.
     *
     * [4단계] 클라이언트 → 서버 흐름:
     * ws.send(JSON.stringify({ type: "CHAT", payload: { content: "안녕" } }))
     * → handleTextMessage() → switch("CHAT") → handleChat()
     *
     * [5단계] 클라이언트 → 서버 흐름:
     * stompClient.send("/app/chat/room-1", {}, JSON.stringify({ sender, content }))
     * → @MessageMapping이 자동 라우팅
     * → switch 분기 없음
     *
     * @MessageMapping("/chat/{roomId}")
     * /app 접두사 + /chat/{roomId} = /app/chat/{roomId} 로 들어온 SEND를 처리한다.
     * (/app 접두사는 WebSocketConfig.setApplicationDestinationPrefixes에서 설정)
     *
     * @DestinationVariable roomId
     *                      destination 경로의 {roomId} 변수를 추출한다.
     *                      Spring MVC의 @PathVariable과 동일한 개념.
     *
     * @Payload SendMessageRequest req
     *          STOMP 프레임 body를 자동으로 역직렬화한다.
     *          4단계의 objectMapper.readTree(message.getPayload())를 대체한다.
     *
     * @param accessor SimpMessageHeaderAccessor
     *                 STOMP 헤더 접근자. sessionId 추출에 사용한다.
     */
    @MessageMapping("/chat/{roomId}")
    public void handleChat(
            @DestinationVariable String roomId,
            @Payload SendMessageRequest req,
            SimpMessageHeaderAccessor accessor) {

        if (!roomRepository.exists(roomId)) {
            log.warn("[STOMP] 존재하지 않는 방: {}", roomId);
            return;
        }

        // 메시지 저장 (ID 자동 발급, timestamp 자동 설정)
        ChatMessage message = roomRepository.save(roomId, req.getSender(), req.getContent());
        log.info("[STOMP] CHAT room={} sender={} id={}", roomId, req.getSender(), message.getId());

        /**
         * 브로드캐스트
         *
         * [4단계] broadcastToRoom() — 30줄짜리 세션 순회 메서드
         * [5단계] 한 줄. SimpleBroker가 /topic/chat/{roomId} 구독자 전체에게 전달
         */
        messagingTemplate.convertAndSend("/topic/chat/" + roomId, message);
    }

    // ── 연결 이벤트 ──────────────────────────────────────────────────────────

    /**
     * STOMP CONNECT 완료 이벤트 처리
     */
    @EventListener
    public void handleConnect(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        log.info("[STOMP] SessionConnected sessionId={}", accessor.getSessionId());
    }

    /**
     * 방 목록 요청 처리 — Request/Response 패턴
     *
     * SessionConnectedEvent에서 바로 전송하면 Race Condition이 발생한다.
     *
     * 문제 흐름:
     * 서버: SessionConnectedEvent → 방 목록 즉시 전송
     * 클라이언트: CONNECTED 수신 → onConnected() → subscribe('/user/queue/room-list')
     * → 서버가 먼저 전송, 클라이언트 구독이 아직 미등록 → 메시지 유실
     *
     * 해결 흐름:
     * 1. 클라이언트: subscribe('/user/queue/room-list') ← 먼저 구독 등록
     * 2. 클라이언트: send('/app/rooms', {}, '') ← 요청 전송
     * 3. 서버: @MessageMapping 수신 → @SendToUser 응답
     * 4. 클라이언트: 콜백으로 방 목록 수신
     */
    @MessageMapping("/rooms")
    @SendToUser("/queue/room-list")
    public List<Map<String, String>> handleRoomListRequest() {
        return roomRepository.getRoomList();
    }

    /**
     * 특정 destination 구독 이벤트 처리
     *
     * 클라이언트가 /topic/chat/{roomId}를 구독하면 "방 입장"으로 간주한다.
     *
     * [4단계] joinRoom() — 6단계 수작업
     * [5단계] /topic/chat/{roomId} 구독 이벤트 감지
     * → 이력 전송 + 입장 알림 브로드캐스트
     */
    @EventListener
    public void handleSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        String sessionId = accessor.getSessionId();

        if (destination == null)
            return;

        // ── 2) 채팅방 구독 감지 = 방 입장 ──────────────────────────────────
        if (!destination.startsWith("/topic/chat/"))
            return;

        String roomId = destination.substring("/topic/chat/".length());
        if (!roomRepository.exists(roomId))
            return;

        String sender = sessionRegistry.getSender(sessionId);
        String roomName = roomRepository.getRoomName(roomId);

        log.info("[STOMP] SUBSCRIBE room={} roomName={} sender={}", roomId, roomName, sender);

        // 최근 메시지 이력 — 구독한 사용자에게만 전송
        List<ChatMessage> recent = roomRepository.getRecentMessages(roomId, 30);
        if (!recent.isEmpty()) {
            messagingTemplate.convertAndSendToUser(
                    sessionId, "/queue/history",
                    recent,
                    Map.of(SimpMessageHeaderAccessor.SESSION_ID_HEADER, sessionId));
        }

        // 입장 알림 — 방 전체에 브로드캐스트
        messagingTemplate.convertAndSend(
                "/topic/chat/" + roomId,
                new SystemMessage(sender + " 님이 [" + roomName + "] 방에 입장했습니다.", 0, "JOINED"));
    }

    /**
     * STOMP DISCONNECT 이벤트 처리
     *
     * [4단계] ChatWebSocketHandler.afterConnectionClosed()
     * → removeSession() → leaveRoom() → broadcastToRoom() 연쇄 호출
     * → 어느 방에 있었는지 추적하기 위해 ChatSession.roomId 필드가 필요했음
     *
     * [5단계] @EventListener(SessionDisconnectEvent)
     * → SessionRegistry에서 닉네임만 꺼내면 됨
     * → 어느 방에 있었는지는 이미 모름 (브로커가 구독 해제를 처리)
     * → 퇴장 알림을 보내려면 SessionRegistry에 마지막 roomId를 저장해야 함
     * (여기서는 단순화를 위해 로깅만 처리)
     */
    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        String sender = sessionRegistry.getSender(sessionId);
        log.info("[STOMP] DISCONNECT sessionId={} sender={}", sessionId, sender);
    }

    /**
     * 방 목록 조회 (HTTP REST, 모니터링용)
     * STOMP 메시지 핸들러이므로 @RestController 대신 별도 컨트롤러에 두는 것이 이상적이나
     * 파일 수를 최소화하기 위해 여기에 포함했다.
     */
    public List<Map<String, String>> getRoomList() {
        return roomRepository.getRoomList();
    }
}
