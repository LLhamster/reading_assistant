package com.example.httpreading.repository;

import java.util.Optional;

import com.example.httpreading.domain.profile.ProfileVectorIndexMapping;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileVectorIndexMappingRepository extends JpaRepository<ProfileVectorIndexMapping, Long> {
    Optional<ProfileVectorIndexMapping> findBySourceTableAndSourceId(String sourceTable, Long sourceId);

    void deleteBySourceTableAndSourceId(String sourceTable, Long sourceId);
}
