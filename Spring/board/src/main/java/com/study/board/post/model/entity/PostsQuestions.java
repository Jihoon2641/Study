package com.study.board.post.model.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
import lombok.Setter;

@Entity
@Table(name = "posts")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostsQuestions {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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

    public static PostsQuestions of(Long userId, String title, String body, String tags, Integer postTypeId,
            Long parentId) {
        PostsQuestions entity = new PostsQuestions();

        entity.title = title;
        entity.body = body;
        entity.acceptedAnswerId = null;
        entity.answerCount = 0;
        entity.commentCount = 0;
        entity.creationDate = LocalDateTime.now();
        entity.lastActivityDate = LocalDateTime.now();
        entity.ownerUserId = userId;
        entity.parentId = parentId;
        entity.postTypeId = postTypeId;
        entity.score = 0;
        entity.tags = tags;
        entity.viewCount = 0;

        return entity;
    }

}
