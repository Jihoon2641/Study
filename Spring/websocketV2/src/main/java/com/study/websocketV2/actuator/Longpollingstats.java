package com.study.websocketV2.actuator;

import java.util.concurrent.atomic.AtomicLong;

import lombok.Getter;

/**
 * Long Polling 통계
 *
 * 1단계(Short Polling)와 비교:
 * - 빈 응답 수: 극적으로 감소 (타임아웃까지 대기하다 응답)
 * - 총 요청 수: 감소 (응답이 오면 즉시 다음 요청 → 타임아웃까지 연결 유지)
 * - 대기 중인 요청 수: 현재 서버에서 붙잡고 있는 연결 수 (핵심 지표)
 */
@Getter
public class Longpollingstats {

    // 총 요청 수
    private final AtomicLong totalRequests = new AtomicLong(0);

    // 타임아웃으로 끝난 요청 (메시지 없이 30초 만료)
    private final AtomicLong timeoutResponses = new AtomicLong(0);

    // 메시지가 생겨서 즉시 응답한 요청
    private final AtomicLong immediateResponses = new AtomicLong(0);

    // 현재 대기 중인 요청 수 (서버 스레드를 점유하고 있음)
    private final AtomicLong pendingRequests = new AtomicLong(0);

    // 최대 동시 대기 요청 수 (최고점)
    private final AtomicLong peakPendingRequests = new AtomicLong(0);

    // 전송된 총 메시지 수
    private final AtomicLong totalMessages = new AtomicLong(0);

    public void requestArrived() {
        totalRequests.incrementAndGet();
        long current = pendingRequests.incrementAndGet();
        // 최고점 갱신
        peakPendingRequests.updateAndGet(peak -> Math.max(peak, current));
    }

    public void requestCompletedWithMessage() {
        immediateResponses.incrementAndGet();
        pendingRequests.decrementAndGet();
    }

    public void requestTimedOut() {
        timeoutResponses.incrementAndGet();
        pendingRequests.decrementAndGet();
    }

    public void messageSent() {
        totalMessages.incrementAndGet();
    }

    /**
     * 타임아웃 비율: Short Polling의 낭비율과 대응되는 지표
     * 낮을수록 좋음 (메시지가 자주 오면 낮아짐)
     */
    public double getTimeoutRatio() {
        long total = totalRequests.get();
        if (total == 0)
            return 0.0;
        return (double) timeoutResponses.get() / total * 100;
    }

}
