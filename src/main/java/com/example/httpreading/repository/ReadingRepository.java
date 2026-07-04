package com.example.httpreading.repository;

import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.httpreading.domain.user.Reading;

public interface ReadingRepository extends JpaRepository<Reading, Long>{

    Optional<Reading> findByBookIdAndUserId(Long bookId, Long userId);

    List<Reading> findByBookId(Long bookId);

    void deleteByBookId(Long bookId);
}
