package com.study.websocketV2.controller;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.http.ResponseEntity;

import com.study.websocketV2.dto.Chatmessage;
import com.study.websocketV2.service.ChatService;
import com.study.websocketV2.actuator.Longpollingstats;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ChatController {

    private final ChatService chatService;

    /**
     * [Long Polling 핵심 엔드포인트]
     *
     * Short Polling과 URL은 같아 보이지만 동작이 완전히 다르다.
     *
     * Short Polling: 즉시 응답 (새 메시지 없으면 빈 배열)
     * Long Polling: 새 메시지가 생길 때까지 응답을 보류
     * → DeferredResult를 반환하면 Spring이 HTTP 연결을 열어둔 채 대기
     *
     * 반환 타입이 DeferredResult<> 인 것이 핵심 포인트:
     * Spring MVC는 이 객체를 받으면 응답을 즉시 보내지 않고,
     * setResult()가 호출될 때까지 연결을 유지한다.
     *
     * @param lastId  마지막으로 받은 메시지 ID
     * @param timeout 클라이언트가 원하는 타임아웃 (ms), 기본 30초
     */
    @GetMapping("/messages/poll")
    public DeferredResult<List<Chatmessage>> longPoll(
            @RequestParam(defaultValue = "0") long lastId,
            @RequestParam(defaultValue = "30000") long timeout) {

        return chatService.waitForMessages(lastId, timeout);
    }

    /**
     * 메시지 전송
     * 전송 즉시 대기 중인 모든 클라이언트에게 Push됨
     */
    @PostMapping("/messages")
    public ResponseEntity<Chatmessage> sendMessage(@RequestBody Map<String, String> body) {
        String sender = body.getOrDefault("sender", "익명");
        String content = body.get("content");

        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(chatService.sendMessage(sender, content));
    }

    /**
     * 초기 메시지 로드
     */
    @GetMapping("/messages")
    public ResponseEntity<List<Chatmessage>> getAllMessages() {
        return ResponseEntity.ok(chatService.getAllMessages());
    }

    /**
     * 통계 엔드포인트
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Longpollingstats stats = chatService.getStats();
        return ResponseEntity.ok(Map.of(
                "totalRequests", stats.getTotalRequests().get(),
                "timeoutResponses", stats.getTimeoutResponses().get(),
                "immediateResponses", stats.getImmediateResponses().get(),
                "pendingRequests", stats.getPendingRequests().get(),
                "peakPendingRequests", stats.getPeakPendingRequests().get(),
                "totalMessages", stats.getTotalMessages().get(),
                "timeoutRatio", String.format("%.1f", stats.getTimeoutRatio())));
    }
}
