package com.study.websocketV4.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {
    private long id;
    private String sender;
    private String content;
    private String timestamp;

    public static ChatMessage of(long id, String sender, String content) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
        return new ChatMessage(id, sender, content, timestamp);
    }
}
