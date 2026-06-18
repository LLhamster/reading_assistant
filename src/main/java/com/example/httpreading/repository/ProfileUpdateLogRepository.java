package com.example.httpreading.repository;

import com.example.httpreading.domain.profile.ProfileUpdateLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileUpdateLogRepository extends JpaRepository<ProfileUpdateLog, Long> {
}
