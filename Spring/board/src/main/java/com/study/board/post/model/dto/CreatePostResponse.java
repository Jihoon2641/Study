package com.study.board.post.model.dto;

import lombok.Getter;

@Getter
public class CreatePostResponse {

    private Long postId;
    private Long userId;
    private String title;
    private String body;
    private String tags;
    private Integer postTypeId;
    private Long parentId;

    public static CreatePostResponse of(Long postId, Long userId, String title, String body, String tags,
            Integer postTypeId, Long parentId) {
        CreatePostResponse newResponse = new CreatePostResponse();

        newResponse.postId = postId;
        newResponse.userId = userId;
        newResponse.title = title;
        newResponse.body = body;
        newResponse.tags = tags;
        newResponse.postTypeId = postTypeId;
        newResponse.parentId = parentId;

        return newResponse;
    }
}
