package com.study.websocketV2.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class Chatmessage {
    private long id;
    private String sender;
    private String content;
    private String timestamp;

    public static Chatmessage of(long id, String sender, String content) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));

        return new Chatmessage(id, sender, content, timestamp);
    }
}
