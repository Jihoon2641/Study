package com.study.websocketV5.interceptor;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import com.study.websocketV5.registry.SessionRegistry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * STOMP 채널 인터셉터
 *
 * 모든 STOMP 프레임이 인바운드 채널을 통과할 때 이 인터셉터가 호출된다.
 * 4단계의 ChatWebSocketHandler.afterConnectionEstablished() /
 * afterConnectionClosed()를 대체하는 역할이다.
 *
 * ─────────────────────────────────────────────────────────────────
 * [4단계 Raw WebSocket]
 * ChatWebSocketHandler.afterConnectionEstablished() → 세션 직접 등록
 * ChatWebSocketHandler.afterConnectionClosed() → 세션 직접 제거
 *
 * [5단계 STOMP]
 * ChannelInterceptor.preSend()
 * → StompCommand.CONNECT : 세션 등록
 * → StompCommand.DISCONNECT : 세션 제거
 * → StompCommand.SUBSCRIBE : 구독 정보 기록
 * ─────────────────────────────────────────────────────────────────
 *
 * 설정 적용은 WebSocketConfig가 아닌 별도의 @Configuration에서 해야 하므로
 * StompChannelConfig 에 등록한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompChannelInterceptor implements ChannelInterceptor {

    /**
     * 현재 연결된 세션 정보를 관리하는 레지스트리
     * ConcurrentHashMap<String, ChatSession>을 대체한다.
     */
    private final SessionRegistry sessionRegistry;

    /**
     * STOMP 프레임이 채널에 전달되기 직전에 호출된다.
     *
     * CONNECT : WebSocket 연결 후 STOMP 핸드셰이크. 닉네임을 헤더에서 추출해 세션에 저장한다.
     * SUBSCRIBE : 클라이언트가 특정 destination을 구독할 때. 구독 현황을 기록한다.
     * DISCONNECT : 클라이언트가 명시적으로 연결을 끊을 때.
     *
     * @param message 전달되는 STOMP 프레임, 헤더(Map구조)와 페이로드(제네릭 타입)로 구성된다.
     * @param channel 메시지가 흐르는 채널
     * @return 변환된 메시지(그대로 반환하면 통과, null 반환하면 차단)
     */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null)
            return message;

        StompCommand command = accessor.getCommand();
        if (command == null)
            return message;

        switch (command) {
            case CONNECT -> {
                String sender = accessor.getFirstNativeHeader("sender");
                String sessionId = accessor.getSessionId();
                sessionRegistry.register(sessionId, sender != null ? sender : "익명");
                log.info("[STOMP] CONNECT sessionId={} sender={}", sessionId, sender);
            }
            case SUBSCRIBE -> {
                String sessionId = accessor.getSessionId();
                String destination = accessor.getDestination();
                sessionRegistry.addSubscription(sessionId, destination);
                log.debug("[STOMP] SUBSCRIBE sessionId={} destination={}", sessionId, destination);
            }
            case DISCONNECT -> {
                String sessionId = accessor.getSessionId();
                sessionRegistry.unregister(sessionId);
                log.info("[STOMP] DISCONNECT sessionId={}", sessionId);
            }
            default -> {
            }
        }

        return message;
    }

}
