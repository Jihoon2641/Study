package com.study.board.post.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class AcceptAnswerRequest {

    @NotNull(message = "answerPostId는 필수입니다.")
    private Long answerPostId;
}
