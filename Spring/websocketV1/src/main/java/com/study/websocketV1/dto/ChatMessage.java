package com.study.websocketV1.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    private long id;
    private String sender;
    private String content;
    private String timestamp;

    public static ChatMessage of(long id, String sender, String content) {
        String timestemp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        return new ChatMessage(id, sender, content, timestemp);
    }

}
