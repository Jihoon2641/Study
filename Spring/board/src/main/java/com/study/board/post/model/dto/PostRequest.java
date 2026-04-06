package com.study.board.post.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;

@Getter
public class PostRequest {

    private String q;
    private Integer postTypeId;
    private Long ownerUserId;
    private String tag;

    @Min(0)
    private int page = 0;

    @Min(1)
    @Max(100)
    private int size = 20;

    @Pattern(regexp = "creationDate|score|viewCount|lastActivityDate")
    private String sort = "creationDate";

    @Pattern(regexp = "asc|desc")
    private String direction = "desc";
}
