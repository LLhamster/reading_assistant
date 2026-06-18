package com.example.httpreading.repository;

import java.util.List;
import java.util.Optional;

import com.example.httpreading.domain.profile.ReadingUnderstandingProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReadingUnderstandingProfileRepository extends JpaRepository<ReadingUnderstandingProfile, Long> {
    Optional<ReadingUnderstandingProfile> findByUserIdAndBookCategory(String userId, String bookCategory);

    List<ReadingUnderstandingProfile> findByUserIdOrderByUpdatedAtDesc(String userId);
}
