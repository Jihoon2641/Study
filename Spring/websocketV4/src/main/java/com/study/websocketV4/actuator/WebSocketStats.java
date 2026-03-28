package com.study.websocketV4.actuator;

import java.util.concurrent.atomic.AtomicLong;

import lombok.Getter;

@Getter
public class WebSocketStats {

    // 총 핸드셰이크 횟수 (연결 수립)
    private final AtomicLong totalConnections = new AtomicLong(0);

    // 현재 활성 연결 수
    private final AtomicLong activeConnections = new AtomicLong(0);

    // 최대 동시 연결 수
    private final AtomicLong peakConnections = new AtomicLong(0);

    // 수신한 프레임 수 (클라이언트 → 서버)
    private final AtomicLong framesReceived = new AtomicLong(0);

    // 전송한 프레임 수 (서버 → 클라이언트)
    private final AtomicLong framesSent = new AtomicLong(0);

    // Ping 수신 횟수
    private final AtomicLong pingsReceived = new AtomicLong(0);

    // 전송된 채팅 메시지 수
    private final AtomicLong chatMessages = new AtomicLong(0);

    // 비정상 연결 종료 횟수
    private final AtomicLong errors = new AtomicLong(0);

    public void connected() {
        totalConnections.incrementAndGet();
        long current = activeConnections.incrementAndGet();
        peakConnections.updateAndGet(p -> Math.max(p, current));
    }

    public void disconnected() {
        activeConnections.decrementAndGet();
    }

    public void frameReceived() {
        framesReceived.incrementAndGet();
    }

    public void frameSent() {
        framesSent.incrementAndGet();
    }

    public void pingReceived() {
        pingsReceived.incrementAndGet();
    }

    public void chatSent() {
        chatMessages.incrementAndGet();
    }

    public void errorOccurred() {
        errors.incrementAndGet();
    }

}
