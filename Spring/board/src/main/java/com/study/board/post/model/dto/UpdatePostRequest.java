package com.study.board.post.model.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class UpdatePostRequest {

    @Size(max = 255)
    private String title;

    private String body;

    @Size(max = 512)
    private String tags;

}
