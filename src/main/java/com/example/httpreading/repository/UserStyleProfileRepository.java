package com.example.httpreading.repository;

import java.util.Optional;

import com.example.httpreading.domain.profile.UserStyleProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserStyleProfileRepository extends JpaRepository<UserStyleProfile, Long> {
    Optional<UserStyleProfile> findByUserId(String userId);
}
