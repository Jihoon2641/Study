package com.study.websocketV5.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import com.study.websocketV5.interceptor.StompChannelInterceptor;

import lombok.RequiredArgsConstructor;

/**
 * STOMP 채널 인터셉터 등록 설정
 * 
 * WebSocketConfig와 분리한 이유:
 * 하나의 WebSocketMessageBrokerConfigurer 구현체에 모든 설정을 몰아 넣으면
 * 빈 순환 참조(StompChannelInterceptor → SessionRegistry → ...)가 발생할 수 있다.
 * 인터셉터 등록은 별도 설정으로 분리하는 것이 안전하다
 * 
 * configureClientInboundChannel()
 * 클라이언트 → 서버 방향 채널(인바운드)에 인터셉터를 등록한다.
 * 모든 STOMP 인바운드 프레임(CONNECT, SEND, SUBSCRIBE, DISCONNECT)이
 * 이 채널을 통과하므로, 여기서 인증·로깅·세션 추적을 처리한다.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class StompChannelConfig implements WebSocketMessageBrokerConfigurer {

    /** 인바운드 채널에 등록할 인터셉터 */
    private final StompChannelInterceptor stompChannelInterceptor;

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompChannelInterceptor);
    }

}
