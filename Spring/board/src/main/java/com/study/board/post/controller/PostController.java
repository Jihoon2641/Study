package com.study.board.post.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.study.board.post.model.dto.CreatePostRequest;
import com.study.board.post.model.dto.CreatePostResponse;
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
}
