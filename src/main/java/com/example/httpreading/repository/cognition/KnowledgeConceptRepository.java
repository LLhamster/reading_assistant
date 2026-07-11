package com.example.httpreading.repository.cognition;

import java.util.List;
import java.util.Optional;

import com.example.httpreading.domain.cognition.ConceptStatus;
import com.example.httpreading.domain.cognition.KnowledgeConcept;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeConceptRepository extends JpaRepository<KnowledgeConcept, Long> {
    Optional<KnowledgeConcept> findFirstByCanonicalNameIgnoreCase(String canonicalName);

    List<KnowledgeConcept> findByNormalizedName(String normalizedName);

    List<KnowledgeConcept> findTop10ByNormalizedNameStartingWithOrNormalizedNameEndingWith(String prefix, String suffix);

    List<KnowledgeConcept> findByBookIdAndStatus(Long bookId, ConceptStatus status);
}
