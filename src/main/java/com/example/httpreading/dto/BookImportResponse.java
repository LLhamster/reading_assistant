package com.example.httpreading.dto;

import java.time.LocalDateTime;

import com.example.httpreading.domain.entity.Books;

public record BookImportResponse(Long id,
                                 String title,
                                 String author,
                                 String coverUrl,
                                 String intro,
                                 String status,
                                 LocalDateTime createdAt,
                                 LocalDateTime updatedAt,
                                 String sourceFilePath,
                                 String sourceFormat,
                                 String parseStatus,
                                 String parseError,
                                 String importDisposition) {

    public static BookImportResponse imported(Books book) {
        return from(book, "IMPORTED");
    }

    public static BookImportResponse duplicate(Books book) {
        return from(book, "DUPLICATE");
    }

    private static BookImportResponse from(Books book, String disposition) {
        return new BookImportResponse(
            book.getId(),
            book.getTitle(),
            book.getAuthor(),
            book.getCoverUrl(),
            book.getIntro(),
            book.getStatus(),
            book.getCreatedAt(),
            book.getUpdatedAt(),
            book.getSourceFilePath(),
            book.getSourceFormat(),
            book.getParseStatus(),
            book.getParseError(),
            disposition);
    }
}
