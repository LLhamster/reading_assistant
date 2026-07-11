package com.example.httpreading.repository.cognition;

import java.util.List;

import com.example.httpreading.domain.cognition.ConceptSource;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConceptSourceRepository extends JpaRepository<ConceptSource, Long> {
    List<ConceptSource> findByConceptIdOrderByCreatedAtDesc(Long conceptId);
}
