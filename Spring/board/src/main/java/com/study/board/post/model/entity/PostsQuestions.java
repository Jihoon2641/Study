package com.study.board.post.model.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import com.study.board.comment.model.Comment;
import com.study.board.user.model.User;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "posts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostsQuestions {

    @Id
    private Long id;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String body;

    @Column(name = "accepted_answer_id")
    private Long acceptedAnswerId;

    @Column(name = "answer_count", nullable = false)
    private Integer answerCount;

    @Column(name = "comment_count", nullable = false)
    private Integer commentCount;

    @Column(name = "creation_date", nullable = false)
    private LocalDateTime creationDate;

    @Column(name = "last_activity_date", nullable = false)
    private LocalDateTime lastActivityDate;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "post_type_id", nullable = false)
    private Integer postTypeId;

    @Column(nullable = false)
    private Integer score;

    @Column(length = 512)
    private String tags;

    @Column(name = "view_count", nullable = false)
    private Integer viewCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id", insertable = false, updatable = false)
    private User ownerUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id", insertable = false, updatable = false)
    private PostsQuestions parentPost;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accepted_answer_id", insertable = false, updatable = false)
    private PostsQuestions acceptedAnswer;

    @OneToMany(mappedBy = "post")
    private List<Comment> comments = new ArrayList<>();

    @OneToMany(mappedBy = "parentPost")
    private List<PostsQuestions> answers = new ArrayList<>();

}
