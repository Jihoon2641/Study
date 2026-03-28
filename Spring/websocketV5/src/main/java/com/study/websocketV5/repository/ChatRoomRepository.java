package com.study.websocketV5.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.study.websocketV5.model.ChatMessage;

import lombok.extern.slf4j.Slf4j;

/**
 * 채팅방 및 메시지 저장소
 *
 * ─────────────────────────────────────────────────────────────────
 * [4단계 Raw WebSocket의 ChatRoom 클래스]
 * - roomId, name
 * - Set<String> sessionIds ← 방에 누가 있는지 직접 관리 (핵심 문제)
 * - List<ChatMessage> messages
 * - addSession(), removeSession(), getMemberCount() ...
 *
 * [5단계 STOMP의 ChatRoomRepository]
 * - sessionIds 집합이 완전히 사라졌다.
 * - STOMP 브로커가 /topic/chat/{roomId} 구독자를 자동으로 추적하므로
 * 서버 코드에서 "방에 누가 있는지"를 직접 관리할 필요가 없다.
 * - 메시지 이력과 방 메타정보만 관리한다.
 * ─────────────────────────────────────────────────────────────────
 */
@Slf4j
@Component
public class ChatRoomRepository {

    /**
     * 전역 메시지 ID 발급기.
     * 스레드 안전하게 순차 ID를 발급한다.
     * 4단계의 ChatService.messageIdGenerator와 동일한 역할이지만
     * 서비스가 아닌 저장소 계층에 위치시켜 단일 책임을 명확히 했다.
     */
    private final AtomicLong messageIdGenerator = new AtomicLong(0);

    /**
     * roomId → 방 이름 매핑.
     * 4단계의 Map<String, ChatRoom>에서 방 목록 역할만 남긴 것.
     * sessionIds 추적이 사라졌으므로 ChatRoom 클래스 자체가 불필요해졌다.
     */
    private final Map<String, String> roomNames = new LinkedHashMap<>(Map.of(
            "general", "일반",
            "random", "자유",
            "tech", "기술"));

    /**
     * roomId → 메시지 이력.
     * 방별로 최근 100개 메시지를 보관한다.
     */
    private final Map<String, CopyOnWriteArrayList<ChatMessage>> messagesByRoom = new ConcurrentHashMap<>();

    /**
     * 새 메시지 ID를 발급하고 방의 메시지 이력에 저장한다.
     *
     * @param roomId  저장할 방 ID
     * @param sender  발신자 닉네임
     * @param content 메시지 본문
     * @return ID와 timestamp가 설정된 ChatMessage
     */
    public ChatMessage save(String roomId, String sender, String content) {
        long id = messageIdGenerator.incrementAndGet();
        ChatMessage message = ChatMessage.of(id, sender, content);

        messagesByRoom.computeIfAbsent(roomId, k -> new CopyOnWriteArrayList<>()).add(message);

        CopyOnWriteArrayList<ChatMessage> messages = messagesByRoom.get(roomId);
        if (messages != null && messages.size() > 100) {
            messages.remove(0);
        }
        return message;
    }

    /**
     * 방의 최근 메시지를 반환한다.
     * 입장 시 이력 전송에 사용한다.
     *
     * @param roomId 조회할 방 ID
     * @param count  가져올 최대 메시지 수
     */
    public List<ChatMessage> getRecentMessages(String roomId, int count) {
        CopyOnWriteArrayList<ChatMessage> messages = messagesByRoom.get(roomId);
        if (messages == null || messages.isEmpty())
            return List.of();

        List<ChatMessage> all = new ArrayList<>(messages);
        int from = Math.max(0, all.size() - count);
        return all.subList(from, all.size());
    }

    /**
     * 전체 방 목록을 반환한다.
     * roomId와 name 정보만 포함된다. memberCount는 브로커가 관리하므로 여기선 알 수 없다.
     */
    public List<Map<String, String>> getRoomList() {
        return roomNames.entrySet().stream()
                .map(e -> Map.of("roomId", e.getKey(), "name", e.getValue()))
                .toList();
    }

    /**
     * roomId가 유효한지 확인한다.
     */
    public boolean exists(String roomId) {
        return roomNames.containsKey(roomId);
    }

    /**
     * 방 이름을 반환한다.
     */
    public String getRoomName(String roomId) {
        return roomNames.getOrDefault(roomId, roomId);
    }
}
