package com.example.httpreading.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.httpreading.domain.user.Reading;

public interface ReadingRepository extends JpaRepository<Reading, Long>{

    Optional<Reading> findByBookIdAndUserId(Long bookId, Long userId);
}