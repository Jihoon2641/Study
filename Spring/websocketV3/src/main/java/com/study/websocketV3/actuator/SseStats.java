package com.study.websocketV3.actuator;

import java.util.concurrent.atomic.AtomicLong;

import lombok.Getter;

@Getter
public class SseStats {

    // 총 SSE 연결 수립 횟수 (페이지 로드 = 1회, 재연결 포함)
    private final AtomicLong totalConnections = new AtomicLong(0);

    // 현재 활성 SSE 스트림 수
    private final AtomicLong activeStreams = new AtomicLong(0);

    // 최대 동시 활성 스트림 수
    private final AtomicLong peakActiveStreams = new AtomicLong(0);

    // 클라이언트가 연결을 끊은 횟수 (브라우저 닫기, 탭 전환 등)
    private final AtomicLong disconnections = new AtomicLong(0);

    // 실제 메시지 이벤트 전송 횟수
    private final AtomicLong messageEventsSent = new AtomicLong(0);

    // Heartbeat 이벤트 전송 횟수 (연결 유지용 코멘트)
    private final AtomicLong heartbeatsSent = new AtomicLong(0);

    // 전송된 총 메시지 수
    private final AtomicLong totalMessages = new AtomicLong(0);

    public void connected() {
        totalConnections.incrementAndGet();
        long current = activeStreams.incrementAndGet();
        peakActiveStreams.updateAndGet(peak -> Math.max(peak, current));
    }

    public void disconnected() {
        disconnections.incrementAndGet();
        activeStreams.decrementAndGet();
    }

    public void eventSent() {
        messageEventsSent.incrementAndGet();
    }

    public void heartbeat() {
        heartbeatsSent.incrementAndGet();
    }

    public void messageSent() {
        totalMessages.incrementAndGet();
    }
}
