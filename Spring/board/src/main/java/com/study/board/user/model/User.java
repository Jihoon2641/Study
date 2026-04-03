package com.study.board.user.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import com.study.board.comment.model.Comment;
import com.study.board.post.model.entity.PostsQuestions;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    private Long id;

    @Column(name = "display_name", length = 128)
    private String displayName;

    @Column(name = "creation_date", nullable = false)
    private LocalDateTime creationDate;

    @Column(name = "last_access_date", nullable = false)
    private LocalDateTime lastAccessDate;

    @Column(nullable = false)
    private Integer reputation;

    @OneToMany(mappedBy = "ownerUser")
    private List<PostsQuestions> ownedPosts = new ArrayList<>();

    @OneToMany(mappedBy = "user")
    private List<Comment> comments = new ArrayList<>();
}
