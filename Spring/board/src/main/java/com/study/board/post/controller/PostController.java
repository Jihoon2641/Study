package com.study.board.post.controller;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.study.board.post.model.dto.CreatePostRequest;
import com.study.board.post.model.dto.CreatePostResponse;
import com.study.board.post.model.dto.PostRequest;
import com.study.board.post.model.dto.PostResponse;
import com.study.board.post.model.dto.UpdatePostRequest;
import com.study.board.post.service.PostService;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;

@RestController
@AllArgsConstructor
@RequestMapping("/api/v1")
public class PostController {

    private final PostService postService;

    @PostMapping("/posts")
    public ResponseEntity<CreatePostResponse> createPost(@RequestBody @Valid CreatePostRequest req) {
        CreatePostResponse newResponse = postService.createPost(req);

        return ResponseEntity.status(HttpStatus.CREATED).body(newResponse);
    }

    @GetMapping("/posts/{id}")
    public ResponseEntity<PostResponse> viewPost(@PathVariable Long id,
            @RequestParam(defaultValue = "true") boolean increaseView) {
        PostResponse newPost = new PostResponse();
        if (increaseView) {
            newPost = postService.getPostIncreaseView(id);
        } else {
            newPost = postService.getPost(id);
        }

        return ResponseEntity.status(HttpStatus.OK).body(newPost);
    }

    @GetMapping("/posts")
    public ResponseEntity<Page<PostResponse>> viewPosts(@ModelAttribute @Valid PostRequest req) {

        Page<PostResponse> newResponse = postService.getPosts(req);

        return ResponseEntity.status(HttpStatus.OK).body(newResponse);
    }

    @PatchMapping("/posts/{id}")
    public ResponseEntity<Object> updatePost(@PathVariable Long id, @RequestBody @Valid UpdatePostRequest req) {

        PostResponse response = postService.updatePost(id, req);

        return ResponseEntity.ok(response);
    }
}
