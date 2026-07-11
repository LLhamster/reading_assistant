package com.example.httpreading.repository.cognition;

import java.util.List;

import com.example.httpreading.domain.cognition.ConceptCandidateRecord;
import com.example.httpreading.domain.cognition.ConceptCandidateStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConceptCandidateRecordRepository extends JpaRepository<ConceptCandidateRecord, Long> {
    List<ConceptCandidateRecord> findByStatusOrderByCreatedAtDesc(ConceptCandidateStatus status);

    List<ConceptCandidateRecord> findByEventIdOrderByCreatedAtDesc(String eventId);
}
