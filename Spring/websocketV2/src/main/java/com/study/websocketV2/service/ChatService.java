package com.study.websocketV2.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

import com.study.websocketV2.actuator.Longpollingstats;
import com.study.websocketV2.dto.Chatmessage;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ChatService {

    private final CopyOnWriteArrayList<Chatmessage> messages = new CopyOnWriteArrayList<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    @Getter
    private final Longpollingstats stats = new Longpollingstats();

    /**
     * [Long Polling 핵심]
     * 현재 대기 중인 DeferredResult 목록.
     * 새 메시지가 오면 여기 있는 모든 대기자에게 즉시 응답을 보낸다.
     *
     * DeferredResult: Spring MVC가 제공하는 비동기 응답 객체.
     * setResult()를 호출하는 순간 클라이언트에게 HTTP 응답이 전송된다.
     */
    private final CopyOnWriteArrayList<PendingRequest> pendingRequests = new CopyOnWriteArrayList<>();

    /**
     * 대기 중인 요청을 감싸는 내부 코드
     */
    private record PendingRequest(DeferredResult<List<Chatmessage>> deferred, long lastId) {
    }

    /**
     * [Long Polling 핵심 엔드포인트 로직]
     *
     * Short Polling과의 결정적 차이:
     * - Short Polling: 새 메시지 없으면 빈 배열 즉시 반환
     * - Long Polling: 새 메시지 없으면 DeferredResult를 대기 목록에 등록하고
     * 메서드를 리턴한다 (HTTP 응답은 아직 안 보냄)
     * → 나중에 메시지가 오면 그때 응답 전송
     *
     * @param lastId    마지막으로 받은 메시지 ID
     * @param timeoutMs 타임아웃 (기본 30초)
     */
    public DeferredResult<List<Chatmessage>> waitForMessages(long lastId, long timeoutMs) {
        DeferredResult<List<Chatmessage>> deferred = new DeferredResult<>(timeoutMs);
        stats.requestArrived();

        // 1. 이미 새 메시지가 있으면 즉시 응답 (대기 불필요)
        List<Chatmessage> existing = getMessagesSince(lastId);
        if (!existing.isEmpty()) {
            deferred.setResult(existing);
            stats.requestCompletedWithMessage();
            log.debug("[LongPoll] lastId={} → 즉시 응답 ({}개)", lastId, existing.size());
            return deferred;
        }

        // 2, 새 메시지 없음 -> 대기 목록에 등록하고 그냥 리턴
        PendingRequest pending = new PendingRequest(deferred, lastId);
        pendingRequests.add(pending);
        log.debug("[LongPoll] lastId={} → 대기 등록 (현재 대기: {}명)", lastId, stats.getPendingRequests().get());

        // 3. 타임아웃 폴백: 30초 동안 메시지 없으면 빈 리스트 응답
        // -> 클라이언트는 받자마자 다시 요청
        deferred.onTimeout(() -> {
            pendingRequests.remove(pending);
            deferred.setResult(List.of());
            stats.requestTimedOut();
            log.debug("[LongPoll] 타임아웃 응답 (대기: {}명)", stats.getPendingRequests().get());
        });

        deferred.onCompletion((() -> pendingRequests.remove(pending)));

        return deferred;
    }

    /**
     * 메시지 전송
     * → 저장 후 대기 중인 모든 클라이언트에게 즉시 Push
     */
    public Chatmessage sendMessage(String sender, String content) {
        long id = idGenerator.incrementAndGet();
        Chatmessage message = Chatmessage.of(id, sender, content);
        messages.add(message);
        stats.messageSent();

        log.info("[Message] id={} sender='{}' content='{}'", id, sender, content);

        // [Long Polling 핵심] 대기 중인 모든 요청에 즉시 응답
        notifyPendingClients(id);

        return message;
    }

    /**
     * 대기 중인 클라이언트에게 새 메시지 알림
     * 각 클라이언트의 lastId 이후 메시지만 골라서 전달
     */
    private void notifyPendingClients(long newMessageId) {
        if (pendingRequests.isEmpty())
            return;

        List<PendingRequest> toNotify = new ArrayList<>(pendingRequests);
        int notified = 0;

        for (PendingRequest pending : toNotify) {
            List<Chatmessage> messages = getMessagesSince(pending.lastId());
            if (!messages.isEmpty() && !pending.deferred().isSetOrExpired()) {
                pending.deferred().setResult(messages);
                pendingRequests.remove(pending);
                stats.requestCompletedWithMessage();
                notified++;
            }
        }

        if (notified > 0) {
            log.debug("[LongPoll] 새 메시지 id={} → {}명에게 즉시 Push", newMessageId, notified);
        }
    }

    private List<Chatmessage> getMessagesSince(long lastId) {
        return messages.stream()
                .filter(msg -> msg.getId() > lastId)
                .toList();
    }

    public List<Chatmessage> getAllMessages() {
        return new ArrayList<>(messages);
    }

}
