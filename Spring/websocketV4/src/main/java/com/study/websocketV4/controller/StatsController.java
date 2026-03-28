package com.study.websocketV4.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.study.websocketV4.service.ChatService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class StatsController {

    private final ChatService chatService;

    /**
     * 대시보드용 통계 엔드포인트
     * WebSocket으로는 통계를 주기적으로 요청하기 불편하므로
     * 모니터링 대시보드는 여전히 HTTP REST로 제공
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(chatService.getStatsDetail());
    }

}
