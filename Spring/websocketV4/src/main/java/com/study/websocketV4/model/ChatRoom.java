package com.study.websocketV4.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import lombok.Getter;

/**
 * 채팅방 모델
 *
 * Raw WebSocket에서는 "방" 개념도 직접 구현해야 한다.
 * - 방에 누가 있는지 (sessionId 집합)
 * - 방의 메시지 이력
 *
 * STOMP에서는 /topic/chat/room-1 구독으로 자동 처리됨
 */
@Getter
public class ChatRoom {

    private final String roomId;
    private final String name;
    private final Set<String> sessionIds = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<ChatMessage> messages = new CopyOnWriteArrayList<>();

    public ChatRoom(String roomId, String name) {
        this.roomId = roomId;
        this.name = name;
    }

    public void addSession(String sessionId) {
        sessionIds.add(sessionId);
    }

    public void removeSession(String sessionId) {
        sessionIds.remove(sessionId);
    }

    public void addMessage(ChatMessage message) {
        messages.add(message);

        // 최근 100개만 유지
        if (messages.size() > 100) {
            messages.remove(0);
        }
    }

    public int getMemberCount() {
        return sessionIds.size();
    }

    public List<ChatMessage> getRecentMessages(int count) {
        List<ChatMessage> all = new ArrayList<>(messages);
        int from = Math.max(0, all.size() - count);
        return all.subList(from, all.size());
    }
}
