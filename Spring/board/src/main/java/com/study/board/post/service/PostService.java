package com.study.board.post.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.study.board.post.model.dto.CreatePostRequest;
import com.study.board.post.model.dto.CreatePostResponse;
import com.study.board.post.model.entity.PostsQuestions;
import com.study.board.post.repository.PostJpaRepository;
import com.study.board.user.repository.UserJpaRepository;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@AllArgsConstructor
@Slf4j
public class PostService {

    private final PostJpaRepository postJpaRepository;
    private final UserJpaRepository userJpaRepository;

    @Transactional
    public CreatePostResponse createPost(CreatePostRequest req) {

        if (req.getPostTypeId() == 2) {
            PostsQuestions parent = postJpaRepository.findById(req.getParentId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "유효한 parentId가 아닙니다."));
            if (parent.getPostTypeId() != 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "답변의 부모는 질문이어야 합니다.");
            }
        }

        if (req.getPostTypeId() == 1 && req.getParentId() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "질문은 parentId를 가질 수 없습니다.");
        }

        userJpaRepository.findById(req.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "유효한 userId가 아닙니다"));

        PostsQuestions newEntity = PostsQuestions.of(req.getUserId(), req.getTitle(), req.getBody(), req.getTags(),
                req.getPostTypeId(), req.getParentId());

        postJpaRepository.save(newEntity);

        return CreatePostResponse.of(
                newEntity.getId(),
                newEntity.getOwnerUserId(),
                newEntity.getTitle(),
                newEntity.getBody(),
                newEntity.getTags(),
                newEntity.getPostTypeId(),
                newEntity.getParentId());
    }

    @Transactional
    public void getPostIncreaseView(Long id) {
        PostsQuestions post = postJpaRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "유효한 id가 아닙니다."));

        Integer viewCount = post.getViewCount() + 1;
        post.setViewCount(viewCount);
    }

    @Transactional(readOnly = true)
    public void getPost(Long id) {
        PostsQuestions post = postJpaRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "유효한 id가 아닙니다."));

        return post;
    }

}
