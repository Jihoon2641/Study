package com.study.websocketV1.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

import com.study.websocketV1.actuator.PollingStats;
import com.study.websocketV1.dto.ChatMessage;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ChatService {

    // 메시지 저장소 실제는 DB 사용
    private final CopyOnWriteArrayList<ChatMessage> messageStore = new CopyOnWriteArrayList<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    @Getter
    private final PollingStats stats = new PollingStats();

    /**
     * [Short Polling]의 핵심 동작]
     * 클라이언트는 마지막으로 받은 메시지 ID(lastId)를 전달한다.
     * 서버는 그 이후의 메시지만 잘라서 즉시 반환한다
     * 새 미지시가 없으면 빈 리스트를 반환한다. -> 낭비
     */
    public List<ChatMessage> getMessagesSince(long lastId) {
        List<ChatMessage> newMessages = messageStore.stream()
                .filter(msg -> msg.getId() > lastId)
                .toList();

        boolean hasMessages = !newMessages.isEmpty();
        stats.recordRequest(hasMessages);

        if (hasMessages) {
            log.debug("[Polling] lastId={} -> {}개 신규 메시지 반환", lastId, newMessages.size());
        } else {
            log.debug("[Polling] lastId={} -> 신규 메시지 없음 (빈 응답 #{} 번째)", lastId, stats.getEmptyResponses().get());
        }

        return newMessages;
    }

    /**
     * 메시지 전송
     */
    public ChatMessage sendMessage(String sender, String content) {
        long id = idGenerator.incrementAndGet();
        ChatMessage message = ChatMessage.of(id, sender, content);
        messageStore.add(message);
        stats.recordMessageSent();

        log.info("[Message] id={} sender='{}' content='{}'", id, sender, content);

        return message;
    }

    /**
     * 전체 메시지 조회 (초기 로드용)
     */
    public List<ChatMessage> getAllMessages() {
        return new ArrayList<>(messageStore);
    }

}
