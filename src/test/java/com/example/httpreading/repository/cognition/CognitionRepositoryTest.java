package com.example.httpreading.repository.cognition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.httpreading.domain.cognition.ConceptResolutionDecision;
import com.example.httpreading.domain.cognition.ConceptResolutionRecord;
import com.example.httpreading.domain.cognition.ConceptStatus;
import com.example.httpreading.domain.cognition.ConfidenceLevel;
import com.example.httpreading.domain.cognition.KnowledgeConcept;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class CognitionRepositoryTest {
    @Autowired
    private KnowledgeConceptRepository conceptRepository;

    @Autowired
    private ConceptResolutionRecordRepository resolutionRepository;

    @Test
    void cognitionEntitiesPersistWithJsonColumns() {
        KnowledgeConcept concept = new KnowledgeConcept();
        concept.setCanonicalName("机会主义");
        concept.setNormalizedName("机会主义");
        concept.setBookId(1L);
        concept.setFirstChapterIndex(1);
        concept.setStatus(ConceptStatus.CONFIRMED);
        KnowledgeConcept saved = conceptRepository.save(concept);

        ConceptResolutionRecord record = new ConceptResolutionRecord();
        record.setEventId("evt-jpa-1");
        record.setUserId("u1");
        record.setBookId(1L);
        record.setChapterIndex(1);
        record.setPrimaryConceptName("机会主义");
        record.setMatchedConceptId(saved.getId());
        record.setCandidateConceptsJson("[{\"conceptId\":1,\"name\":\"机会主义\",\"score\":0.91}]");
        record.setConfidence(0.88d);
        record.setConfidenceLevel(ConfidenceLevel.HIGH);
        record.setDecision(ConceptResolutionDecision.LINK_EXISTING);
        record.setScoreBreakdownJson("{\"modelScore\":0.85}");
        record.setContextEvidenceJson("[\"划词与概念一致\"]");
        resolutionRepository.save(record);

        assertTrue(resolutionRepository.findByEventId("evt-jpa-1").isPresent());
        assertEquals(1, conceptRepository.findByNormalizedName("机会主义").size());
    }
}
