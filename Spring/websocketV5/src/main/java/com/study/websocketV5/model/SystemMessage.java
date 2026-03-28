package com.study.websocketV5.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 시스템 알림 메시지 모델
 *
 * 입장·퇴장·에러 등 서버가 단방향으로 알리는 이벤트에 사용한다.
 *
 * [4단계] WsMessage.of(TYPE_SYSTEM, Map.of("text", "...", "memberCount", N))
 * → Map을 직접 조립하고 WsMessage 봉투에 넣어 전송
 *
 * [5단계] SystemMessage 객체를 직접 반환하거나 convertAndSend()로 전송
 * → 별도 봉투 없이 타입이 명확한 객체 사용
 * → Spring이 JSON 직렬화 담당
 */
@Getter
@AllArgsConstructor
public class SystemMessage {

    /** 사용자에게 보여줄 시스템 메시지 텍스트 */
    private String text;

    /** 이벤트 발생 시점의 방 인원 수 */
    private int memberCount;

    /** 이벤트 종류 구분자 (JOINED / LEFT / INFO) */
    private String eventType;
}
