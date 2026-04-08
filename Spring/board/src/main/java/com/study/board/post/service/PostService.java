package com.study.board.post.service;

import java.time.LocalDateTime;
import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.study.board.post.model.dto.CreatePostRequest;
import com.study.board.post.model.dto.CreatePostResponse;
import com.study.board.post.model.dto.PostRequest;
import com.study.board.post.model.dto.PostResponse;
import com.study.board.post.model.dto.UpdatePostRequest;
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
    public PostResponse getPostIncreaseView(Long id) {
        int updated = postJpaRepository.incrementViewCount(id);

        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "유효한 id가 아닙니다.");
        }

        PostsQuestions post = postJpaRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "유효한 id가 아닙니다."));

        return PostResponse.from(post);
    }

    @Transactional(readOnly = true)
    public PostResponse getPost(Long id) {
        PostsQuestions post = postJpaRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "유효한 id가 아닙니다."));

        return PostResponse.from(post);
    }

    @Transactional(readOnly = true)
    public Page<PostResponse> getPosts(PostRequest req) {
        Pageable pageable = PageRequest.of(
                req.getPage(),
                req.getSize(),
                Sort.Direction.fromString(req.getDirection()), req.getSort());

        Page<PostsQuestions> posts = postJpaRepository.findAll(
                normalize(req.getQ()),
                req.getPostTypeId(),
                req.getOwnerUserId(),
                normalize(req.getTag()),
                pageable);

        return posts.map(PostResponse::from);
    }

    private String normalize(String value) {
        if (value == null)
            return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Transactional
    public PostResponse updatePost(Long id, UpdatePostRequest req) {
        PostsQuestions post = postJpaRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "유효한 id가 아닙니다."));

        if (req.getTitle() != null)
            post.setTitle(req.getTitle());
        if (req.getBody() != null)
            post.setBody(req.getBody());
        if (req.getTags() != null)
            post.setTags(req.getTags());

        post.setLastActivityDate(LocalDateTime.now());

        return PostResponse.from(post);
    }

    @Transactional
    public PostResponse deletePost(Long id) {
        PostsQuestions post = postJpaRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "유효한 id가 아닙니다."));

        if (post.getPostTypeId() == 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "답변이 있는 게시물은 지울 수 없습니다.");
        }

        postJpaRepository.deleteById(id);

        return PostResponse.from(post);
    }

    @Transactional
    public PostResponse acceptAnswer(Long id, Long answerPostId) {
        PostsQuestions question = postJpaRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "유효한 id가 아닙니다."));

        if (question.getPostTypeId() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "질문 게시물만 채택 답변을 설정할 수 있습니다.");
        }

        PostsQuestions answer = postJpaRepository.findById(answerPostId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "유효한 answerPostId가 아닙니다."));

        if (answer.getPostTypeId() != 2 || !Objects.equals(answer.getParentId(), question.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "해당 질문의 답변만 채택할 수 있습니다.");
        }

        question.setAcceptedAnswerId(answerPostId);
        question.setLastActivityDate(LocalDateTime.now());

        return PostResponse.from(question);
    }

}
