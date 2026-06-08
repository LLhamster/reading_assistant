package com.example.httpreading.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.httpreading.domain.entity.Chapters;

public interface ChaptersRepository extends JpaRepository<Chapters, Long>{
    
    public List<Chapters> findByBookIdOrderByChapterIndexAsc(Long bookid);

    public Optional<Chapters> findByBookIdAndChapterIndex(Long bookId, Integer chapterId);

    public void deleteByBookId(Long bookId);

}
