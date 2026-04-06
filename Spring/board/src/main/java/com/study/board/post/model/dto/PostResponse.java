package com.study.board.post.model.dto;

import java.time.LocalDateTime;

import com.study.board.post.model.entity.PostsQuestions;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PostResponse {

    private Long id;
    private String title;
    private String body;
    private Long acceptedAnswerId;
    private Integer answerCount;
    private Integer commentCount;
    private LocalDateTime creationDate;
    private LocalDateTime lastActivityDate;
    private Long ownerUserId;
    private Long parentId;
    private Integer postTypeId;
    private Integer score;
    private String tags;
    private Integer viewCount;

    public static PostResponse from(PostsQuestions entity) {
        PostResponse response = new PostResponse();

        response.id = entity.getId();
        response.title = entity.getTitle();
        response.body = entity.getBody();
        response.acceptedAnswerId = entity.getAcceptedAnswerId();
        response.answerCount = entity.getAnswerCount();
        response.commentCount = entity.getCommentCount();
        response.creationDate = entity.getCreationDate();
        response.lastActivityDate = entity.getLastActivityDate();
        response.ownerUserId = entity.getOwnerUserId();
        response.parentId = entity.getParentId();
        response.postTypeId = entity.getPostTypeId();
        response.score = entity.getScore();
        response.tags = entity.getTags();
        response.viewCount = entity.getViewCount();

        return response;
    }
}