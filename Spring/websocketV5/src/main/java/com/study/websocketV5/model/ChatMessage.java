package com.study.websocketV5.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 채팅 메시지 도메인 모델
 *
 * 4단계와 구조가 동일하지만 역할이 달라졌다.
 *
 * [4단계] WsMessage 봉투 안에 payload로 들어가는 내부 객체
 * → objectMapper.writeValueAsString(WsMessage.of("CHAT", chatMessage))
 * → 직렬화를 직접 처리
 *
 * [5단계] @MessageMapping 메서드의 반환값 또는 파라미터로 직접 사용
 * → Spring이 자동으로 JSON 직렬화/역직렬화
 * → WsMessage 봉투가 필요 없다. STOMP 프레임의 destination이 "타입" 역할을 한다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    /** 서버가 발급하는 전역 순차 ID */
    private long id;

    /** 발신자 닉네임 */
    private String sender;

    /** 메시지 본문 */
    private String content;

    /** 서버 수신 시각 (HH:mm:ss.SSS) */
    private String timestamp;

    /**
     * 정적 팩토리 메서드.
     * 서버에서 메시지를 생성할 때 사용. timestamp를 자동 설정한다.
     */
    public static ChatMessage of(long id, String sender, String content) {
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
        return new ChatMessage(id, sender, content, timestamp);
    }

}
