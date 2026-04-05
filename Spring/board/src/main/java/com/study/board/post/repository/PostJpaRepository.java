package com.study.board.post.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.study.board.post.model.entity.PostsQuestions;

public interface PostJpaRepository extends JpaRepository<PostsQuestions, Long> {

}
