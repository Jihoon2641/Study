package com.study.websocketV4.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.study.websocketV4.actuator.WebSocketStats;
import com.study.websocketV4.model.ChatMessage;
import com.study.websocketV4.model.ChatRoom;
import com.study.websocketV4.model.WsMessage;
import com.study.websocketV4.session.ChatSession;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ObjectMapper objectMapper;

    @Getter
    private final WebSocketStats stats = new WebSocketStats();

    // sessionId -> ChatSession
    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

    // roomId -> ChatRoom
    private final Map<String, ChatRoom> rooms = new ConcurrentHashMap<>();

    private final AtomicLong messageIdGenerator = new AtomicLong(0);

    // 초기 방 생성
    {
        rooms.put("general", new ChatRoom("general", "일반"));
        rooms.put("random", new ChatRoom("random", "자유"));
        rooms.put("tech", new ChatRoom("tech", "기술"));
    }

    // 세션 관리

    public void registerSession(WebSocketSession session) {
        ChatSession chatSession = new ChatSession(session);
        sessions.put(session.getId(), chatSession);
        stats.connected();

        log.info("[WS] 연결 수립 sessionId={} (활성: {})", session.getId(), stats.getActiveConnections().get());
    }

    public void removeSession(String sessionId) {
        ChatSession chatSession = sessions.remove(sessionId);

        stats.disconnected();

        // 방에서도 제거 + 퇴장 알림
        if (chatSession.isInRoom()) {
            leaveRoom(chatSession);
        }

        log.info("[WS] 연결 종료 sessionId={} sender={} (활성: {})",
                sessionId, chatSession.getSender(), stats.getActiveConnections().get());
    }

    // 방 입장/퇴장

    /**
     * [Raw WebSocket 문제점 체감]
     *
     * 방 입장 하나에 이렇게 많은 작업을 수동으로 해야 한다:
     * 1. 이전 방 퇴장 처리
     * 2. 새 방 세션 등록
     * 3. 입장 확인 메시지 전송
     * 4. 다른 멤버들에게 입장 알림 브로드캐스트
     * 5. 최근 메시지 이력 전송
     * 6. 현재 방 멤버 목록 전송
     *
     * STOMP에서는 SUBSCRIBE /topic/chat/room-1 한 줄로 끝남
     */
    public void joinRoom(ChatSession chatSession, String roomId, String sender) {
        ChatRoom room = rooms.get(roomId);

        if (room == null) {
            sendError(chatSession, "존재하지 않는 방: " + roomId);
            return;
        }

        // 이전 방 퇴장
        if (chatSession.isInRoom() && !chatSession.getRoomId().equals(roomId)) {
            leaveRoom(chatSession);
        }

        chatSession.setSender(sender);
        chatSession.setRoomId(roomId);
        room.addSession(chatSession.getSessionId());

        log.info("[WS] 입장 sender={} room={} (방 인원: {})", sender, roomId, room.getMemberCount());

        // 입장 확인 이벤트 전송(나에게만)
        send(chatSession, WsMessage.of(WsMessage.TYPE_JOINED, Map.of(
                "roomId", roomId,
                "roomName", room.getName(),
                "memberCount", room.getMemberCount(),
                "recentMessages", room.getRecentMessages(30))));

        // 입장 알림 브로드캐스트 (나를 포함한 방 전체)
        broadcastToRoom(roomId, WsMessage.of(WsMessage.TYPE_SYSTEM, Map.of(
                "text", sender + " 님이 입장했습니다.",
                "memberCount", room.getMemberCount())));
    }

    public void leaveRoom(ChatSession chatSession) {
        String roomId = chatSession.getRoomId();
        if (roomId == null)
            return;

        ChatRoom room = rooms.get(roomId);
        if (room != null) {
            room.removeSession(chatSession.getSessionId());

            // 퇴장 알림 브로드 캐스트
            String sender = chatSession.getSender() != null ? chatSession.getSender() : "익명";
            broadcastToRoom(roomId, WsMessage.of(WsMessage.TYPE_SYSTEM, Map.of(
                    "text", sender + " 님이 퇴장했습니다.",
                    "memberCount", room.getMemberCount())));
        }

        chatSession.setRoomId(null);
        log.info("[WS] 퇴장 sender={} room={}", chatSession.getSender(), roomId);
    }

    // 메시지 전송 브로드캐스트

    public void handleChat(ChatSession chatSession, String content) {
        if (!chatSession.isInRoom()) {
            sendError(chatSession, "방에 입장하지 않았습니다.");
            return;
        }

        String roomId = chatSession.getRoomId();
        ChatRoom room = rooms.get(roomId);

        if (room == null)
            return;

        long id = messageIdGenerator.incrementAndGet();
        ChatMessage message = ChatMessage.of(id, chatSession.getSender(), content);
        room.addMessage(message);
        stats.chatSent();
        chatSession.incrementMessageCount();

        log.debug("[WS] 채팅 room={} sender={} id={}", roomId, chatSession.getSender(), id);

        broadcastToRoom(roomId, WsMessage.of(WsMessage.TYPE_CHAT, message));
    }

    /**
     * [Raw WebSocket 문제점]
     * 방 브로드캐스트를 직접 구현해야 한다.
     * - 방의 sessionId 목록을 순회
     * - 각 세션을 sessions 맵에서 찾아서
     * - 직접 TextMessage로 직렬화해서 send
     *
     * STOMP에서는 messagingTemplate.convertAndSend("/topic/chat/room-1", msg) 한 줄
     */
    public void broadcastToRoom(String roomId, WsMessage message) {
        ChatRoom room = rooms.get(roomId);
        if (room == null)
            return;

        List<String> deadSessions = new ArrayList<>();

        for (String sessionId : room.getSessionIds()) {
            ChatSession target = sessions.get(sessionId);

            if (target == null || !target.isOpen()) {
                deadSessions.add(sessionId);
                continue;
            }

            send(target, message);
        }

        // 죽은 세션 정리
        deadSessions.forEach(id -> {
            room.removeSession(id);
            sessions.remove(id);
        });
    }

    // Ping Pong
    public void handlePing(ChatSession chatSession) {
        chatSession.setLastPingTime(System.currentTimeMillis());
        stats.pingReceived();
        send(chatSession, WsMessage.of(WsMessage.TYPE_PONG, Map.of("time", System.currentTimeMillis())));
        log.debug("[WS] Ping/Pong sessionId={}", chatSession.getSessionId());
    }

    /**
     * Dead Connection 감지
     * 30초 이상 Ping이 없으면 연결이 죽은 것으로 판단
     */
    @Scheduled(fixedDelay = 15000)
    public void detectDeadConnections() {
        long threshold = System.currentTimeMillis() - 30_000;
        List<String> dead = new ArrayList<>();

        for (ChatSession chatSession : sessions.values()) {
            if (chatSession.getLastPingTime() < threshold) {
                dead.add(chatSession.getSessionId());
                log.warn("[WS] Dead connection 감지 sessionId={} sender={}",
                        chatSession.getSessionId(), chatSession.getSender());
            }
        }

        dead.forEach(id -> {
            ChatSession cs = sessions.get(id);
            if (cs != null) {
                try {
                    cs.getSession().close();
                } catch (IOException e) {
                    removeSession(id);
                }
            }
        });

        if (!dead.isEmpty()) {
            log.info("[WS] Dead connection {}개 정리 완료", dead.size());
        }
    }

    // 방 목록
    public void sendRoomList(ChatSession chatSession) {
        List<Map<String, Object>> roomList = rooms.values().stream()
                .map(r -> Map.<String, Object>of(
                        "roomId", r.getRoomId(),
                        "name", r.getName(),
                        "memberCount", r.getMemberCount()))
                .toList();

        send(chatSession, WsMessage.of(WsMessage.TYPE_ROOM_LIST, roomList));
    }

    // 저수준 전송

    /**
     * [Raw WebSocket 핵심]
     * 모든 데이터를 JSON 문자열로 직접 직렬화해서 TextMessage로 감싸 전송
     * 프레임 타입(텍스트/바이너리)도 직접 선택해야 함
     *
     * STOMP에서는 직렬화, 프레임 선택, 헤더 추가를 프레임워크가 처리
     */
    public void send(ChatSession chatSession, WsMessage message) {
        if (!chatSession.isOpen())
            return;

        try {
            String json = objectMapper.writeValueAsString(message);
            chatSession.getSession().sendMessage(new TextMessage(json));
            stats.frameSent();
        } catch (IOException e) {
            log.warn("[WS] 전송 실패 sessionId={}: {}", chatSession.getSessionId(), e.getMessage());
            stats.errorOccurred();
        }
    }

    public void sendError(ChatSession chatSession, String errorMessage) {
        send(chatSession, WsMessage.of(WsMessage.TYPE_ERROR, Map.of("message", errorMessage)));
    }

    // 조회
    public ChatSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public Map<String, Object> getStatsDetail() {
        return Map.of(
                "totalConnections", stats.getTotalConnections().get(),
                "activeConnections", stats.getActiveConnections().get(),
                "peakConnections", stats.getPeakConnections().get(),
                "framesReceived", stats.getFramesReceived().get(),
                "framesSent", stats.getFramesSent().get(),
                "pingsReceived", stats.getPingsReceived().get(),
                "chatMessages", stats.getChatMessages().get(),
                "errors", stats.getErrors().get(),
                "rooms", rooms.values().stream().map(r -> Map.of(
                        "roomId", r.getRoomId(),
                        "name", r.getName(),
                        "memberCount", r.getMemberCount())).toList(),
                "sessions", sessions.values().stream().map(s -> Map.of(
                        "sessionId", s.getSessionId(),
                        "sender", s.getSender() != null ? s.getSender() : "—",
                        "roomId", s.getRoomId() != null ? s.getRoomId() : "—",
                        "connectedAt", s.getConnectedAt(),
                        "messageCount", s.getMessageCount())).toList());
    }
}
