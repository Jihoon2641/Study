package com.study.websocketV5;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Scheduler 삭제 -> Dead Connection 감지를 직접 할 필요가 없어졌다.
 * STOMP 브로커가 연결 상태를 관리하고, Hearbeat도 설정 한 줄로 처리한다.
 */
@SpringBootApplication
public class WebsocketV5Application {

	public static void main(String[] args) {
		SpringApplication.run(WebsocketV5Application.class, args);
	}

}
