package com.study.websocketV4.handler;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.study.websocketV4.model.WsMessage;
import com.study.websocketV4.service.ChatService;
import com.study.websocketV4.session.ChatSession;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * [Raw WebSocket 핵심 클래스]
 *
 * TextWebSocketHandler를 상속해서 아래 이벤트를 처리한다:
 *
 * afterConnectionEstablished : 핸드셰이크 완료, 연결 수립
 * handleTextMessage : 텍스트 프레임 수신
 * handleBinaryMessage : 바이너리 프레임 수신 (여기선 미사용)
 * handlePongMessage : Pong 프레임 수신 (WebSocket 레벨 Pong)
 * afterConnectionClosed : 연결 종료
 * handleTransportError : 전송 오류
 *
 * SSE에서는 이런 핸들러가 없었다.
 * SSE는 서버 → 클라이언트 단방향이었기 때문에
 * 클라이언트에서 뭔가 보내면 별도 HTTP POST 엔드포인트로 받았다.
 *
 * WebSocket은 양방향이므로 클라이언트 → 서버 메시지도
 * 이 핸들러 하나에서 처리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    /**
     * HTTP Upgrade 핸드셰이크 완료 → WebSocket 연결 수립
     *
     * 이 시점부터 HTTP가 아닌 WebSocket 프로토콜로 통신
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        chatService.registerSession(session);
        chatService.getStats().frameReceived();

        // 연결 직후 방 목록 전송
        ChatSession chatSession = chatService.getSession(session.getId());
        chatService.sendRoomList(chatSession);

        log.info("[WS] 연결 수립 sessionId={} remoteAddr={}",
                session.getId(), session.getRemoteAddress());
    }

    /**
     * 텍스트 프레임 수신
     *
     * [Raw WebSocket 문제점]
     * 메시지가 오면 type을 직접 파싱해서 분기해야 한다.
     * type 필드를 안 만들었다면 내용을 보고 판단해야 하는데
     * 이게 점점 복잡해진다.
     *
     * STOMP에서는 COMMAND 필드가 이 역할을 표준화해서 처리
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        chatService.getStats().frameReceived();
        ChatSession chatSession = chatService.getSession(session.getId());
        if (chatSession == null)
            return;

        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            String type = root.path("type").asString("익명");
            JsonNode payload = root.path("payload");

            // type 기반 라우팅
            switch (type) {
                case WsMessage.TYPE_JOIN -> {
                    String roomId = payload.path("roomId").asString();
                    String sender = payload.path("sender").asString("익명");
                    chatService.joinRoom(chatSession, roomId, sender);
                }
                case WsMessage.TYPE_CHAT -> {
                    String content = payload.path("content").asString();
                    if (!content.isBlank()) {
                        chatService.handleChat(chatSession, content);
                    }
                }
                case WsMessage.TYPE_LEAVE -> chatService.leaveRoom(chatSession);
                case WsMessage.TYPE_PING -> chatService.handlePing(chatSession);
                default -> {
                    log.warn("[WS] 알 수 없는 메시지 타입: {}", type);
                    chatService.sendError(chatSession, "알 수 없는 타입: " + type);
                }
            }
        } catch (Exception e) {
            log.error("[WS] 메시지 파싱 실패: {}", e.getMessage());
            chatService.sendError(chatSession, "잘못된 메시지 형식");
            chatService.getStats().errorOccurred();
        }
    }

    /**
     * WebSocket 레벨 Pong 프레임 수신
     * (우리가 구현한 애플리케이션 레벨 PING/PONG과는 별개)
     */
    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        ChatSession chatSession = chatService.getSession(session.getId());
        if (chatSession != null) {
            chatSession.setLastPingTime(System.currentTimeMillis());
        }
        log.debug("[WS] Pong 수신 (WebSocket 레벨) sessionId={}", session.getId());
    }

    /**
     * 연결 종료 (정상/비정상 모두)
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        chatService.removeSession(session.getId());
        log.info("[WS] 연결 종료 sessionId={} status={}", session.getId(), status);
    }

    /**
     * 전송 오류
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        chatService.getStats().errorOccurred();
        log.error("[WS] 전송 오류 sessionId={}: {}", session.getId(), exception.getMessage());
        chatService.removeSession(session.getId());
    }
}
