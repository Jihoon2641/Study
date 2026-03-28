package com.study.websocketV5.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP WebSocket 설정
 *
 * ─────────────────────────────────────────────────
 * [4단계 Raw WebSocket과의 비교]
 *
 * 4단계: WebSocketConfigurer 구현
 * → addHandler(chatWebSocketHandler, "/ws/chat")
 * → ChatWebSocketHandler 직접 연결
 * → 브로드캐스트, 방 관리, Dead Connection 감지 모두 직접 구현
 *
 * 5단계: WebSocketMessageBrokerConfigurer 구현
 * → addStompEndpoints("/ws/chat") : 연결 엔드포인트만 선언
 * → configureMessageBroker(registry) : 브로커 설정만 선언
 * → 그 이후의 라우팅·브로드캐스트·연결 관리는 Spring이 처리
 * ─────────────────────────────────────────────────
 *
 * EnableWebSocketMessageBroker
 * WebSocket + STOMP + 메시지 브로커를 한 번에 활성화하는 어노테이션.
 * 4단계의 @EnableWebSocket과 달리 메시지 브로커 계층이 추가된다.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * STOMP 연결 엔드포인트 등록
     * 
     * 클라이언트는 ws://host/ws/chat으로 WebSocket 연결을 맺는다
     * 핸드셰이크 이후 STOMP 프레임으로 통신 시작
     * 
     * withSockJS()
     * WebSocket을 지원하지 않는 구현 브라우저나 프록시 환경에서
     * HTTP Streaming → Long Polling 순으로 자동 폴백하는 SockJS를 활성화한다.
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/chat")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    /**
     * 메시지 브로커 설정
     *
     * 4단계에서 직접 구현했던 것들이 이 메서드 하나로 대체된다:
     *
     * ┌─────────────────────────────────────────────────────────────┐
     * │ 4단계 직접 구현 │ 5단계 브로커 설정 │
     * ├─────────────────────────────────┼──────────────────────────┤
     * │ ConcurrentHashMap<roomId, room> │ /topic/** 구독 자동 관리 │
     * │ broadcastToRoom() 순회 전송 │ SimpleBroker가 처리 │
     * │ Dead Connection 스케줄러 │ setHeartbeatValue 설정 │
     * │ switch(type) 라우팅 분기 │ @MessageMapping 어노테이션 │
     * └─────────────────────────────────┴──────────────────────────┘
     *
     * enableSimpleBroker("/topic", "/queue")
     * 인메모리 SimpleMessageBroker를 활성화한다.
     * /topic/** : Publish-Subscribe (1:N 브로드캐스트). 채팅방 메시지에 사용.
     * /queue/** : Point-to-Point (1:1). 특정 사용자에게만 보낼 때 사용.
     * setHeartbeatValue: 브로커 ↔ 클라이언트 Heartbeat 주기(ms).
     * 4단계의 @Scheduled detectDeadConnections()를 이 한 줄이 대체한다.
     *
     * setApplicationDestinationPrefixes("/app")
     * 클라이언트가 /app/** 으로 SEND하면 @MessageMapping 메서드로 라우팅된다.
     * /app 이 없는 /topic/** 구독은 브로커로 직접 전달된다.
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[] { 10000, 10000 })
                .setTaskScheduler(brokerTaskScheduler());

        registry.setApplicationDestinationPrefixes("/app");

        // /user/** : SimpMessagingTemplate.convertAndSendToUser() 로 특정 유저에게 전송할 때 사용
        registry.setUserDestinationPrefix("/user");
    }

    /**
     * Heartbeat 전송에 필요한 TaskScheduler 빈 등록
     *
     * setHeartbeatValue()를 설정하면 SimpleBroker가 주기적으로 Heartbeat 프레임을
     * 전송하기 위해 내부적으로 TaskScheduler를 필요로 한다.
     *
     * ThreadPoolTaskScheduler: Spring이 제공하는 기본 스케줄러 구현체.
     * Heartbeat 용도이므로 스레드 풀 크기 1로 충분하다.
     */
    @Bean
    public ThreadPoolTaskScheduler brokerTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("stomp-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }

}
