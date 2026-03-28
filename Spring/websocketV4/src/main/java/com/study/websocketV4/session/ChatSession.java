package com.study.websocketV4.session;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.web.socket.WebSocketSession;

import lombok.Getter;
import lombok.Setter;

/**
 * WebSocketSession 래퍼
 *
 * Raw WebSocket에서는 세션 관리를 직접 해야 한다.
 * - 어느 방에 있는지
 * - 누구인지
 * - 언제 연결했는지
 * - Ping/Pong 상태
 *
 * STOMP에서는 이 모든 것이 프레임 헤더와 브로커가 처리한다.
 */
@Getter
public class ChatSession {

    private final WebSocketSession session;
    private final String sessionId;
    private final String connectedAt;

    @Setter
    private String sender; // 닉네임
    @Setter
    private String roomId; // 현재 입장한 방 ID
    @Setter
    private long lastPingTime; // 마지막 Ping 수신 시각

    private long messageCount = 0;

    public ChatSession(WebSocketSession session) {
        this.session = session;
        this.sessionId = session.getId();
        this.connectedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        this.lastPingTime = System.currentTimeMillis();
    }

    public void incrementMessageCount() {
        messageCount++;
    }

    public boolean isInRoom() {
        return roomId != null;
    }

    public boolean isOpen() {
        return session.isOpen();
    }

}
