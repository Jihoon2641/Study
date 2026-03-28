package com.study.websocketV3;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WebsocketV3Application {

	public static void main(String[] args) {
		SpringApplication.run(WebsocketV3Application.class, args);
	}

}
