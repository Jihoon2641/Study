package com.study.websocketV4.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * [Raw WebSocket의 핵심 문제]
 *
 * Raw WebSocket은 그냥 텍스트/바이너리 프레임만 주고받는다.
 * "이 메시지가 채팅인지, 입장 알림인지, 에러인지" 를 구분할 방법이 없다.
 *
 * 그래서 직접 프로토콜(봉투 포맷)을 설계해야 한다:
 * {
 * "type": "CHAT" | "JOIN" | "LEAVE" | "ERROR" | "PING" | "ROOM_LIST",
 * "payload": { ... } // type에 따라 다른 구조
 * }
 *
 * → 이 문제를 표준화한 것이 STOMP (5단계에서 다룸)
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class WsMessage {

    private String type;
    private Object payload;

    // 클라이언트 -> 서버 수신용
    public static WsMessage of(String type, Object payload) {
        return new WsMessage(type, payload);
    }

    /**
     * 클라이언트 → 서버로 오는 메시지 타입
     *
     * JOIN : 채팅방 입장 요청 { roomId, sender }
     * CHAT : 메시지 전송 { roomId, sender, content }
     * LEAVE : 채팅방 퇴장
     * PING : 연결 확인용 (서버가 PONG으로 응답)
     *
     * 서버 → 클라이언트로 보내는 메시지 타입
     *
     * CHAT : 채팅 메시지 브로드캐스트
     * JOINED : 입장 확인
     * LEFT : 퇴장 알림
     * ROOM_LIST: 방 목록
     * ERROR : 에러
     * PONG : PING 응답
     * SYSTEM : 시스템 메시지 (입장/퇴장 알림)
     */

    public static final String TYPE_JOIN = "JOIN";
    public static final String TYPE_CHAT = "CHAT";
    public static final String TYPE_LEAVE = "LEAVE";
    public static final String TYPE_PING = "PING";
    public static final String TYPE_JOINED = "JOINED";
    public static final String TYPE_LEFT = "LEFT";
    public static final String TYPE_ROOM_LIST = "ROOM_LIST";
    public static final String TYPE_ERROR = "ERROR";
    public static final String TYPE_PONG = "PONG";
    public static final String TYPE_SYSTEM = "SYSTEM";

}
