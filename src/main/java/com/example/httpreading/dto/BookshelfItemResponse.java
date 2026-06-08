package com.example.httpreading.dto;

import java.time.LocalDateTime;

import com.example.httpreading.domain.entity.Books;

public class BookshelfItemResponse {
    private Books book;
    private String ragStatus;
    private LocalDateTime ragStartedAt;
    private LocalDateTime ragFinishedAt;
    private String ragError;

    public BookshelfItemResponse(Books book,
                                 String ragStatus,
                                 LocalDateTime ragStartedAt,
                                 LocalDateTime ragFinishedAt,
                                 String ragError) {
        this.book = book;
        this.ragStatus = ragStatus == null || ragStatus.isBlank() ? "NONE" : ragStatus;
        this.ragStartedAt = ragStartedAt;
        this.ragFinishedAt = ragFinishedAt;
        this.ragError = ragError;
    }

    public Books getBook() {
        return book;
    }

    public void setBook(Books book) {
        this.book = book;
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
