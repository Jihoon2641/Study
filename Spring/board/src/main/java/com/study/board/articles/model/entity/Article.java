package com.study.board.articles.model.entity;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;

@Entity
public class Article {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "article_text", columnDefinition = "LONGTEXT")
    private String text;

    @Column(name = "url", columnDefinition = "LONGTEXT")
    private String url;

    @Column(name = "authors", nullable = false, columnDefinition = "LONGTEXT")
    private String authors;

    @Column(name = "article_timestamp", updatable = false)
    private String timestamp;

    @OneToMany(mappedBy = "article", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ArticleTag> tags = new ArrayList<>();

}
