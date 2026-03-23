package com.study.websocketV1.actuator;

import java.util.concurrent.atomic.AtomicLong;

import lombok.Getter;

/**
 * Short Polling의 문제점을 시각화하기 위한 통계 모델
 * - 총 HTTP 요청 수 (메시지 없어도 계속 요청)
 * - 빈 응답 수 (새 메시지 없는데 요청한 횟수)
 * - 실제 메시지 수신 수
 */
@Getter
public class PollingStats {

    // 총 풀링 요청 횟수
    private final AtomicLong totalRequests = new AtomicLong(0);

    // 아무 메시지도 없었던 요청 횟수 -> 낭비된 요청
    private final AtomicLong emptyResponses = new AtomicLong(0);

    // 실제 메시지를 담은 응답 횟수
    private final AtomicLong messagesDelivered = new AtomicLong(0);

    // 전송된 총 메시지 개수
    private final AtomicLong totalMessages = new AtomicLong(0);

    public void recordRequest(boolean hasMessages) {
        totalRequests.incrementAndGet();

        if (hasMessages) {
            messagesDelivered.incrementAndGet();
        } else {
            emptyResponses.incrementAndGet();
        }
    }

    public void recordMessageSent() {
        totalMessages.incrementAndGet();
    }

    /**
     * 낭비율 = (빈 응답 수 / 전체 요청 수) * 100
     * Short Polling의 핵심 문제 : 이 값이 매우 높음
     */
    public double getWasteRatio() {
        long total = totalRequests.get();
        if (total == 0)
            return 0.0;
        return (double) emptyResponses.get() / total * 100;
    }

}
