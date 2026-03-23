package com.study.websocketV1.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.study.websocketV1.actuator.PollingStats;
import com.study.websocketV1.dto.ChatMessage;
import com.study.websocketV1.service.ChatService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class Chatcontroller {

    private final ChatService chatService;

    /**
     * [Short Polling 핵심 엔드포인트]
     *
     * 클라이언트는 1초마다 이 API를 호출한다.
     * - 새 메시지 있음 → 메시지 반환 (유의미한 응답)
     * - 새 메시지 없음 → 빈 배열 반환 (낭비된 요청)
     *
     * 서버는 요청을 받는 즉시 응답한다. (대기하지 않음)
     *
     * @param lastId 클라이언트가 마지막으로 받은 메시지 ID
     */
    @GetMapping("/messages/poll")
    public ResponseEntity<Map<String, Object>> poll(@RequestParam(defaultValue = "0") long lastId) {
        List<ChatMessage> messages = chatService.getMessagesSince(lastId);
        PollingStats stats = chatService.getStats();

        return ResponseEntity.ok(Map.of(
                "messages", messages,
                "hasNew", !messages.isEmpty(),
                "stats", Map.of(
                        "totalRequests", stats.getTotalRequests().get(),
                        "emptyResponses", stats.getEmptyResponses().get(),
                        "messagesDelivered", stats.getMessagesDelivered().get(),
                        "wasteRatio", String.format("%.1f", stats.getWasteRatio()))));
    }

    /**
     * 메시지 전송 엔드포인트
     * Short Polling 에서는 메시지 전송과 수신이 완전한 분리된 별도 HTTP 요청
     */
    @PostMapping("/messages")
    public ResponseEntity<ChatMessage> sendMessage(@RequestBody Map<String, String> body) {
        String sender = body.get("sender");
        String content = body.get("content");

        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        ChatMessage message = chatService.sendMessage(sender, content);
        return ResponseEntity.ok(message);
    }

    /**
     * 초기 메시지 로드 (페이지 첫 진입 시 사용)
     */
    @GetMapping("/messages")
    public ResponseEntity<List<ChatMessage>> getAllEntity() {
        return ResponseEntity.ok(chatService.getAllMessages());
    }

    /**
     * 통계 전용 엔드포인트 (대시보드에서 호출)
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        PollingStats stats = chatService.getStats();

        return ResponseEntity.ok(Map.of(
                "totalRequests", stats.getTotalRequests().get(),
                "emptyResponses", stats.getEmptyResponses().get(),
                "messagesDelivered", stats.getMessagesDelivered().get(),
                "totalMessages", stats.getTotalMessages().get(),
                "wasteRatio", String.format("%.1f", stats.getWasteRatio())));
    }

    /**
     * 테스트용: 메시지 초기화
     */
    @DeleteMapping("/messages")
    public ResponseEntity<Void> clearMessages() {
        log.warn("[Admin] 메시지 및 통계 초기화 요청");
        chatService.getAllMessages().clear(); // 실제로는 service에 clear 메서드 추가 권장
        return ResponseEntity.noContent().build();
    }
}
