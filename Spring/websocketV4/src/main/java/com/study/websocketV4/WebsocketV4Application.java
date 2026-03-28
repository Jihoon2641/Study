package com.study.websocketV4;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WebsocketV4Application {

	public static void main(String[] args) {
		SpringApplication.run(WebsocketV4Application.class, args);
	}

}
