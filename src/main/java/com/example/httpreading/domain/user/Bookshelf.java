package com.example.httpreading.domain.user;


import java.time.LocalDateTime;

import jakarta.persistence.*;

@Entity
public class Bookshelf {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "book_id")
    private Long bookId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "rag_status")
    private String ragStatus;

    @Column(name = "rag_started_at")
    private LocalDateTime ragStartedAt;

    @Column(name = "rag_finished_at")
    private LocalDateTime ragFinishedAt;

    @Column(name = "rag_error", columnDefinition = "text")
    private String ragError;

        public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getRagStatus() {
        return ragStatus;
    }

    public void setRagStatus(String ragStatus) {
        this.ragStatus = ragStatus;
    }

    public LocalDateTime getRagStartedAt() {
        return ragStartedAt;
    }

    public void setRagStartedAt(LocalDateTime ragStartedAt) {
        this.ragStartedAt = ragStartedAt;
    }

    public LocalDateTime getRagFinishedAt() {
        return ragFinishedAt;
    }

    public void setRagFinishedAt(LocalDateTime ragFinishedAt) {
        this.ragFinishedAt = ragFinishedAt;
    }

    public String getRagError() {
        return ragError;
    }

    public void setRagError(String ragError) {
        this.ragError = ragError;
    }

}
