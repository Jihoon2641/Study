package com.study.websocketV4.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import com.study.websocketV4.handler.ChatWebSocketHandler;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebsocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;

    /**
     * [Raw WebSocket 설정]
     *
     * SSE와의 구조적 차이:
     * - SSE: @GetMapping → SseEmitter 반환 → HTTP 연결 유지
     * - Raw WebSocket: WebSocketHandler 등록 → Spring이 HTTP Upgrade 처리
     *
     * /ws 엔드포인트로 WebSocket 연결 요청이 오면
     * HTTP → WebSocket 핸드셰이크 후 ChatWebSocketHandler로 위임
     *
     * setAllowedOrigins("*"): 개발 편의를 위해 모든 Origin 허용
     * 프로덕션에서는 허용할 도메인만 명시
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws/chat").setAllowedOrigins("*");
    }

}
