package com.example.httpreading.repository.cognition;

import java.util.Optional;

import com.example.httpreading.domain.cognition.ConceptMergeRelation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConceptMergeRelationRepository extends JpaRepository<ConceptMergeRelation, Long> {
    Optional<ConceptMergeRelation> findFirstBySourceConceptIdOrderByCreatedAtDesc(Long sourceConceptId);
}
