package com.study.websocketV5.registry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SessionRegistry {

    /**
     * sessionId -> 닉네임 매핑
     * STOMP CONNECT 프레임의 'sender' 헤더로 등록된다
     * ChatSession.sender 필드 대체
     */
    private final Map<String, String> senderBySession = new ConcurrentHashMap<>();

    /**
     * sessionId → 구독 중인 destination 집합.
     * 4단계의 ChatSession.roomId 필드를 대체한다.
     * roomId 하나만 저장하던 것과 달리, 여러 destination 동시 구독이 가능하다.
     */
    private final Map<String, Set<String>> subscriptionsBySession = new ConcurrentHashMap<>();

    /**
     * STOMP CONNECT 시 세션을 등록한다.
     * StompChannelInterceptor에서 호출된다.
     * 
     * @param sessionId STOMP 세션 ID
     * @param sender    CONNECT 헤더에서 추출한 닉네임
     */
    public void register(String sessionId, String sender) {
        senderBySession.put(sessionId, sender);
        subscriptionsBySession.put(sessionId, ConcurrentHashMap.newKeySet());
    }

    /**
     * STOMP DISCONNECT 시 세션을 제거한다.
     * StompChannelInterceptor에서 호출된다.
     *
     * 4단계에서는 removeSession()이 leaveRoom()을 연쇄 호출해
     * 퇴장 알림 브로드캐스트까지 직접 처리했다.
     * STOMP에서는 이 책임이 ChatController의 이벤트 리스너로 분리된다.
     *
     * @param sessionId 종료된 STOMP 세션 ID
     */
    public void unregister(String sessionId) {
        senderBySession.remove(sessionId);
        subscriptionsBySession.remove(sessionId);
    }

    /**
     * STOMP SUBSCRIBE 시 구독 정보를 기록한다.
     *
     * @param sessionId   구독을 요청한 세션 ID
     * @param destination 구독 destination (/topic/chat/room-1 등)
     */
    public void addSubscription(String sessionId, String destination) {
        subscriptionsBySession.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(destination);
    }

    /**
     * 세션 ID로 닉네임을 조회한다.
     * 
     * @return 닉네임. 등록되지 않은 세션이면 "익명" 반환
     */
    public String getSender(String sessionId) {
        return senderBySession.getOrDefault(sessionId, "익명");
    }

    /**
     * 현재 연결된 전체 세션 수를 반환한다.
     * 모니터링 API에서 사용한다.
     */
    public int getActiveCount() {
        return senderBySession.size();
    }

    /**
     * 모니터링 API용 세션 목록 반환.
     * sessionId, sender, 구독 목록을 포함한다.
     */
    public List<Map<String, Object>> getSessionInfo() {
        return senderBySession.entrySet().stream()
                .map(e -> {
                    Set<String> subs = subscriptionsBySession.getOrDefault(e.getKey(), Set.of());
                    return Map.<String, Object>of(
                            "sessionId", e.getKey(),
                            "sender", e.getValue(),
                            "subscriptions", subs);
                })
                .toList();
    }
}
