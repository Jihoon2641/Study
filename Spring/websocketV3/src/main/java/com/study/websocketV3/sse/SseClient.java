package com.study.websocketV3.sse;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.Getter;

/**
 * SSE 연결 단위를 표현하는 모델
 *
 * Long Polling의 DeferredResult와 대응되지만 핵심적으로 다름:
 * - DeferredResult: 응답 1번 보내면 끝 → 다시 요청해야 함
 * - SseEmitter: 응답을 여러 번 보낼 수 있음 → 연결 유지
 */
@Getter
public class SseClient {

    private final String clientId;
    private final SseEmitter emitter;
    private final String connectedAt;
    private long lastEventId;

    public SseClient(String clientId, SseEmitter emitter, long lastEventId) {
        this.clientId = clientId;
        this.emitter = emitter;
        this.lastEventId = lastEventId;
        this.connectedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    public void updateLastEventId(long id) {
        this.lastEventId = id;
    }
}
