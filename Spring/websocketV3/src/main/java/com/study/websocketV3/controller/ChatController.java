package com.study.websocketV3.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.study.websocketV3.actuator.SseStats;
import com.study.websocketV3.dto.ChatMessage;
import com.study.websocketV3.service.SseService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@Slf4j
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ChatController {

    private final SseService sseService;

    /**
     * [SSE 핵심 엔드포인트]
     *
     * 반환 타입: SseEmitter
     * Content-Type: text/event-stream ← 브라우저가 자동으로 SSE로 인식
     *
     * 1단계 Short Polling: GET /poll → 즉시 응답 → 연결 종료
     * 2단계 Long Polling: GET /poll → 대기 → 응답 → 연결 종료 → 재요청
     * 3단계 SSE: GET /subscribe → 연결 유지 → 메시지마다 이벤트 Push
     * ↑ 이 연결 하나로 모든 메시지 수신
     *
     * Last-Event-ID 헤더:
     * 브라우저가 SSE 연결 끊길 때 자동으로 마지막 수신 이벤트 ID를 기억했다가
     * 재연결 시 이 헤더로 전송 → 놓친 메시지를 서버가 재전송 가능
     */
    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@RequestHeader(value = "Last-Event-ID", defaultValue = "0") long lastEventId) {
        return sseService.subscribe(lastEventId);
    }

    /**
     * 메시지 전송 (클라이언트 → 서버)
     *
     * SSE의 한계: 서버 → 클라이언트만 가능 (단방향)
     * 클라이언트 → 서버는 여전히 별도 HTTP POST 필요
     * → 이것이 SSE의 근본적 한계 (4단계 WebSocket으로 해결)
     */
    @PostMapping("/messages")
    public ResponseEntity<ChatMessage> sendMessage(@RequestBody Map<String, String> body) {
        String sender = body.getOrDefault("sender", "익명");
        String content = body.get("content");

        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(sseService.sendMessage(sender, content));
    }

    @GetMapping("/messages")
    public ResponseEntity<List<ChatMessage>> getAllMessages() {
        return ResponseEntity.ok(sseService.getAllMessages());
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        SseStats stats = sseService.getStats();
        return ResponseEntity.ok(Map.of(
                "totalConnections", stats.getTotalConnections().get(),
                "activeStreams", stats.getActiveStreams().get(),
                "peakActiveStreams", stats.getPeakActiveStreams().get(),
                "disconnections", stats.getDisconnections().get(),
                "messageEventsSent", stats.getMessageEventsSent().get(),
                "heartbeatsSent", stats.getHeartbeatsSent().get(),
                "totalMessages", stats.getTotalMessages().get(),
                "clients", sseService.getClientInfo()));

    }

}
