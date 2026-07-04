package com.example.httpreading.repository;

import java.util.List;
import java.util.Optional;

import com.example.httpreading.domain.profile.ProfileGrowthEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileGrowthEvidenceRepository extends JpaRepository<ProfileGrowthEvidence, Long> {
    List<ProfileGrowthEvidence> findByUserIdAndEvidenceDomainAndStatusOrderByCreatedAtDesc(
        String userId,
        String evidenceDomain,
        String status);

    List<ProfileGrowthEvidence> findByUserIdAndStatusOrderByCreatedAtDesc(String userId, String status);

    Optional<ProfileGrowthEvidence> findByRelatedAnnotationId(Long relatedAnnotationId);

    List<ProfileGrowthEvidence> findByUserIdAndEvidenceTypeAndStatusOrderByUpdatedAtDesc(
        String userId, String evidenceType, String status);

    List<ProfileGrowthEvidence> findByRelatedBookId(Long relatedBookId);
}
