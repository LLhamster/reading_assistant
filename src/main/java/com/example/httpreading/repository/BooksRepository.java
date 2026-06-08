package com.example.httpreading.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.httpreading.domain.entity.Books;

public interface BooksRepository extends JpaRepository<Books, Long> {
    public Page<Books> findByTitleContainingIgnoreCaseOrAuthorContainingIgnoreCase(
        String titleKey,
        String authorKey,
        Pageable pageable
    );
    public Page<Books> findByStatus(String status, Pageable pageable);
    
} 