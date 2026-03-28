package com.study.websocketV3.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.study.websocketV3.actuator.SseStats;
import com.study.websocketV3.dto.ChatMessage;
import com.study.websocketV3.sse.SseClient;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class SseService {

    private final CopyOnWriteArrayList<ChatMessage> messageStore = new CopyOnWriteArrayList<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    @Getter
    private final SseStats stats = new SseStats();

    /**
     * [SSE 핵심] 현재 연결된 모든 클라이언트의 SseEmitter 보관소
     *
     * Long Polling과의 구조적 차이:
     * - Long Polling: DeferredResult 목록 → 응답 후 즉시 제거
     * - SSE: SseEmitter 맵 → 명시적으로 complete()할 때까지 유지
     */
    private final Map<String, SseClient> clients = new ConcurrentHashMap<>();

    /**
     * [SSE 핵심 엔드포인트 로직]
     *
     * SseEmitter를 생성해 반환하면 Spring이 HTTP 연결을 유지한 채
     * 이후 emitter.send()를 호출할 때마다 데이터를 스트리밍한다.
     *
     * HTTP 응답 헤더:
     * Content-Type: text/event-stream ← SSE 전용 Content-Type
     * Cache-Control: no-cache
     * Connection: keep-alive
     *
     * @param lastEventId Last-Event-ID 헤더 (브라우저 자동 재연결 시 마지막 수신 ID 전달)
     */
    public SseEmitter subscribe(long lastEventId) {
        String clientId = UUID.randomUUID().toString().substring(0, 8);

        // 타임아웃 0L = 무제한
        SseEmitter emitter = new SseEmitter(0L);
        SseClient client = new SseClient(clientId, emitter, lastEventId);

        clients.put(clientId, client);
        stats.connected();

        log.info("[SSE] 연결 수립 clientId={} lastEventId={} (활성: {}명)",
                clientId, lastEventId, stats.getActiveStreams().get());

        // 연결 해제 콜백 등록
        emitter.onCompletion(() -> removeClient(clientId, "completion"));
        emitter.onTimeout(() -> removeClient(clientId, "timeout"));
        emitter.onError(e -> removeClient(clientId, "error: " + e.getMessage()));

        // 연결 직후: 연결 확인 이벤트 전송
        sendEvent(emitter, "connected", Map.of(
                "clientId", clientId,
                "message", "SSE 연결 수립 완료"));

        // 재연결 시: 놓친 메시지 재전송 (Last-Event_ID 활용)
        if (lastEventId > 0) {
            replayMissedMessage(client);
        }

        return emitter;
    }

    /**
     * 메시지 전송
     * → 저장 후 연결된 모든 클라이언트에게 SSE 이벤트로 즉시 Push
     *
     * Long Polling과의 차이:
     * - Long Polling: DeferredResult.setResult() → 연결 종료 → 재연결 필요
     * - SSE: emitter.send() → 연결 유지 → 다음 메시지도 같은 연결로
     */
    public ChatMessage sendMessage(String sender, String content) {
        long id = idGenerator.incrementAndGet();
        ChatMessage message = ChatMessage.of(id, sender, content);
        messageStore.add(message);
        stats.messageSent();

        log.info("[Message] id={} sender='{}' → {}명에게 Push", id, sender, clients.size());

        broadcastMessage(message);
        return message;
    }

    /**
     * 연결된 모든 클라이언트에게 메시지 이벤트 브로드캐스트
     */
    private void broadcastMessage(ChatMessage message) {
        List<String> deadClients = new ArrayList<>();

        for (Map.Entry<String, SseClient> entry : clients.entrySet()) {
            String clientId = entry.getKey();
            SseClient client = entry.getValue();

            try {
                // SseEmitter.event() 빌더로 이벤트 구성
                // id: 클라이언트가 Last-Event-ID 헤더로 재연결 시 활용
                // event: 클라이언트가 addEventListener('message', ...) 로 수신
                // data: 실제 payload
                client.getEmitter().send(
                        SseEmitter.event()
                                .id(String.valueOf(message.getId()))
                                .name("message")
                                .data(message));

                client.updateLastEventId(message.getId());
                stats.eventSent();
            } catch (IOException e) {
                log.warn("[SSE] 전송 실패 client={} -> 제거", clientId);
                deadClients.add(clientId);
            }
        }

        // 전송 실패한 끊어진 연결 정리
        deadClients.forEach(id -> removeClient(id, "send failed"));
    }

    /**
     * [SSE 핵심] Heartbeat - 연결 유지 메커니즘
     *
     * HTTP 중간 프록시, 로드밸런서, 방화벽은 일정 시간 동안
     * 데이터 전송이 없으면 연결을 강제로 끊는다.
     *
     * SSE 스펙의 코멘트 라인(": comment\n\n")을 주기적으로 전송해
     * 연결이 살아있음을 알린다. 클라이언트 측에서는 이벤트로 처리되지 않음.
     *
     * Long Polling에는 이 개념이 없음 (30초마다 자동 재연결이 대신 처리)
     */
    @Scheduled(fixedDelay = 15000)
    public void sendHeartbeat() {
        if (clients.isEmpty())
            return;

        List<String> deadClients = new ArrayList<>();

        for (Map.Entry<String, SseClient> entry : clients.entrySet()) {
            try {
                // SSE 코멘트 라인 전송: ": heartbeat\n\n"
                // 클라이언트에서는 onmessage 이벤트 발생 안 함 → 순수 연결 유지용
                entry.getValue().getEmitter().send(
                        SseEmitter.event()
                                .comment("heartbeat"));
                stats.heartbeat();
            } catch (IOException e) {
                deadClients.add(entry.getKey());
            }
        }

        deadClients.forEach(id -> removeClient(id, "heartbeat failed"));

        if (!clients.isEmpty()) {
            log.debug("[SSE] Heartbeeat 전송 -> {}명", clients.size());
        }
    }

    /**
     * 재연결 시 놓친 메시지 전송
     * Last-Event-ID 이후의 메시지를 순서대로 전송
     */
    private void replayMissedMessage(SseClient client) {
        List<ChatMessage> missed = messageStore.stream()
                .filter(m -> m.getId() > client.getLastEventId())
                .toList();

        if (missed.isEmpty())
            return;

        log.info("[SSE] 재연결 clientId={} → 놓친 메시지 {}개 재전송", client.getClientId(), missed.size());

        for (ChatMessage msg : missed) {
            try {
                client.getEmitter().send(
                        SseEmitter.event()
                                .id(String.valueOf(msg.getId()))
                                .name("message")
                                .data(msg));
                stats.eventSent();
            } catch (IOException e) {
                log.warn("[SSE] 재전송 실패 clientId={}", client.getClientId());
                break;
            }
        }
    }

    private void removeClient(String clientId, String reason) {
        if (clients.remove(clientId) != null) {
            stats.disconnected();
            log.info("[SSE] 연결 종료 clientId={} reason={} (활성: {}명)",
                    clientId, reason, stats.getActiveStreams().get());
        }
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            log.warn("[SSE] 초기 이벤트 전송 실패: {}", e.getMessage());
        }
    }

    public List<ChatMessage> getAllMessages() {
        return new ArrayList<>(messageStore);
    }

    public int getActiveClientCount() {
        return clients.size();
    }

    public List<Map<String, Object>> getClientInfo() {
        return clients.values().stream()
                .map(c -> Map.<String, Object>of(
                        "clientId", c.getClientId(),
                        "connectedAt", c.getConnectedAt(),
                        "lastEventId", c.getLastEventId()))
                .toList();
    }

}
