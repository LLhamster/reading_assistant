package com.example.httpreading.repository;

import java.util.List;

import com.example.httpreading.domain.profile.ProfileGrowthEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileGrowthEvidenceRepository extends JpaRepository<ProfileGrowthEvidence, Long> {
    List<ProfileGrowthEvidence> findByUserIdAndEvidenceDomainAndStatusOrderByCreatedAtDesc(
        String userId,
        String evidenceDomain,
        String status);

    List<ProfileGrowthEvidence> findByUserIdAndStatusOrderByCreatedAtDesc(String userId, String status);
}
