package com.study.websocketV5.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.study.websocketV5.registry.SessionRegistry;
import com.study.websocketV5.repository.ChatRoomRepository;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class StatsController {

    /** 현재 연결된 세션 목록과 구독 정보 제공 */
    private final SessionRegistry sessionRegistry;

    /** 방 목록과 메시지 이력 제공 */
    private final ChatRoomRepository roomRepository;

    /**
     * 전체 현황 조회
     * activeConnections, 세션 목록, 방 목록을 반환한다.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(Map.of(
                "activeConnections", sessionRegistry.getActiveCount(),
                "sessions", sessionRegistry.getSessionInfo(),
                "rooms", roomRepository.getRoomList()));
    }

}
