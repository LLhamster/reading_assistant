package com.example.httpreading.repository.cognition;

import java.util.Optional;

import com.example.httpreading.domain.cognition.ConceptResolutionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConceptResolutionRecordRepository extends JpaRepository<ConceptResolutionRecord, Long> {
    Optional<ConceptResolutionRecord> findByEventId(String eventId);

    boolean existsByEventId(String eventId);
}
