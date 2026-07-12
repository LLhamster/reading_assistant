package com.example.httpreading.dto.mobile;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;

import com.example.httpreading.domain.entity.Books;
import com.example.httpreading.domain.entity.Chapters;

public final class MobileDtos {
    private MobileDtos() {}

    public record MobilePageResponse<T>(
        List<T> content,
        int page,
        int pageSize,
        long totalElements,
        int totalPages,
        boolean last
    ) {
        public static <T> MobilePageResponse<T> from(Page<T> page) {
            return new MobilePageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast());
        }
    }

    public record MobileBookSummary(
        Long id,
        String title,
        String author,
        String coverUrl,
        String intro,
        String status,
        LocalDateTime updatedAt
    ) {
        public static MobileBookSummary from(Books book) {
            if (book == null) {
                return null;
            }
            return new MobileBookSummary(
                book.getId(),
                book.getTitle(),
                book.getAuthor(),
                book.getCoverUrl(),
                book.getIntro(),
                book.getStatus(),
                book.getUpdatedAt());
        }
    }

    public record MobileChapterSummary(
        Long id,
        Long bookId,
        Integer chapterIndex,
        String title,
        Integer volumeIndex,
        String volumeTitle,
        Integer hierarchyLevel
    ) {
        public static MobileChapterSummary from(Chapters chapter) {
            if (chapter == null) {
                return null;
            }
            return new MobileChapterSummary(
                chapter.getId(),
                chapter.getBookId(),
                chapter.getChapterIndex(),
                chapter.getTitle(),
                chapter.getVolumeIndex(),
                chapter.getVolumeTitle(),
                chapter.getHierarchyLevel());
        }
    }

    public record MobileChapterContent(
        Long id,
        Long bookId,
        Integer chapterIndex,
        String title,
        String content,
        String contentHtml,
        Integer volumeIndex,
        String volumeTitle
    ) {
        public static MobileChapterContent from(Chapters chapter) {
            if (chapter == null) {
                return null;
            }
            return new MobileChapterContent(
                chapter.getId(),
                chapter.getBookId(),
                chapter.getChapterIndex(),
                chapter.getTitle(),
                chapter.getContent(),
                chapter.getContentHtml(),
                chapter.getVolumeIndex(),
                chapter.getVolumeTitle());
        }
    }
}
