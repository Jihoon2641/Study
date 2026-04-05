package com.study.board.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.study.board.user.model.User;

@Repository
public interface UserJpaRepository extends JpaRepository<User, Long> {

}
