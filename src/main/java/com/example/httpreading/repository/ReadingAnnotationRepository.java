package com.example.httpreading.repository;

import java.util.List;
import java.util.Optional;

import com.example.httpreading.domain.user.ReadingAnnotation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReadingAnnotationRepository extends JpaRepository<ReadingAnnotation, Long> {
    List<ReadingAnnotation> findByUserIdAndBookIdOrderByUpdatedAtDesc(Long userId, Long bookId);

    List<ReadingAnnotation> findByUserIdAndBookIdAndChapterIndexOrderByStartOffsetAsc(
        Long userId, Long bookId, Integer chapterIndex);

    Optional<ReadingAnnotation> findByIdAndUserId(Long id, Long userId);

    List<ReadingAnnotation> findByBookId(Long bookId);

    void deleteByBookId(Long bookId);
}
