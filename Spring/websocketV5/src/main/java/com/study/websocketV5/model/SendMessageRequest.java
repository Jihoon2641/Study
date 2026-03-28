package com.study.websocketV5.model;

import lombok.Getter;

/**
 * 클라이언트 → 서버 메시지 전송 요청 모델
 *
 * ─────────────────────────────────────────────────────────
 * [4단계] 클라이언트가 보내는 프레임 구조:
 * {
 * "type": "CHAT", ← 메시지 종류를 직접 명시
 * "payload": {
 * "content": "안녕하세요"
 * }
 * }
 * → 서버에서 type을 파싱해 switch 분기
 *
 * [5단계] 클라이언트가 보내는 STOMP 프레임:
 * SEND
 * destination:/app/chat/room-1 ← destination이 "타입"이자 "라우팅 주소"
 * content-type:application/json
 *
 * {"sender":"Alice","content":"안녕하세요"}^@
 *
 * → destination으로 @MessageMapping이 자동 라우팅
 * → type 필드, switch 분기 완전 제거
 * ─────────────────────────────────────────────────────────
 */
@Getter
public class SendMessageRequest {
    /**
     * 발신자 닉네임
     * STOMP CONNECT 헤더에서도 받지만, 메시지 본문에도 포함해 편의성을 높인다.
     */
    private String sender;

    /** 메시지 본문 */
    private String content;

}
