package com.study.board.post.model.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CreatePostRequest {

    @NotNull
    private Long userId;

    @NotBlank(message = "제목은 빈 글자면 안됩니다.")
    @Size(max = 255, message = "제목은 255자를 초과할 수 없습니다.")
    private String title;

    @NotBlank(message = "본문은 필 수 입니다.")
    private String body;

    @Pattern(regexp = "^$|^[a-zA-Z0-9]+(\\|[a-zA-Z0-9]+)*$", message = "태그 형태가 잘못되었습니다.")
    @Size(max = 512, message = "태그의 길이는 최대 길이는 512자 입니다.")
    private String tags;

    @NotNull
    private Integer postTypeId;

    private Long parentId;

    @AssertTrue(message = "postTypeId가 2이면 parentId는 필수 입니다.")
    public boolean isValidParentId() {
        return this.postTypeId != null && (this.postTypeId != 2 || this.parentId != null);
    }

    @AssertTrue(message = "postTypeId는 1,2 둘 중에 하나여야 합니다.")
    public boolean isValidPostTypeId() {
        return this.postTypeId != null && (this.postTypeId == 1 || this.postTypeId == 2);
    }
}
