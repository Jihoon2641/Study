package com.study.board.post.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.study.board.post.model.entity.PostsQuestions;

public interface PostJpaRepository extends JpaRepository<PostsQuestions, Long> {

    /**
     * flushAutomatically = true
     * 영속성 컨텍스트에 변경된 내용이 있으면 DB에 먼저 반영(flush)하고 UPDATE 실행 -> 순서 꼬임 방지
     * clearAutomatically = true
     * UPDATE 쿼리는 영속성 컨텍스트를 거치지 않고 DB에 직접 반영되기 때문에 1차 캐시와 DB 데이터 불일치 발생
     * -> 쿼리 실행 후 1차 캐시를 비워서 불일치 방지
     * 
     * @param id
     * @return
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PostsQuestions p set p.viewCount = p.viewCount + 1 where p.id = :id")
    int incrementViewCount(@Param("id") Long id);

    @Query("""
                select p
                from PostsQuestions p
                where (:q is null or trim(:q) = ''
                    or lower(p.title) like lower(concat('%', :q, '%'))
                    or lower(p.body) like lower(concat('%', :q, '%')))
                and (:postTypeId is null or p.postTypeId = :postTypeId)
                and (:ownerUserId is null or p.ownerUserId = :ownerUserId)
                and (:tag is null or trim(:tag) = ''
                    or concat('|', coalesce(p.tags, ''), '|') like concat('%|', :tag, '|%'))
            """)
    Page<PostsQuestions> findAll(
            @Param("q") String q,
            @Param("postTypeId") Integer postTypeId,
            @Param("ownerUserId") Long ownerUserId,
            @Param("tag") String tag,
            Pageable pageable);
}
