package com.example.httpreading.service.cognition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.example.httpreading.domain.cognition.ConceptCandidateRecord;
import com.example.httpreading.domain.cognition.ConceptCandidateStatus;
import com.example.httpreading.domain.cognition.ConceptMergeRelation;
import com.example.httpreading.domain.cognition.ConceptStatus;
import com.example.httpreading.domain.cognition.KnowledgeConcept;
import com.example.httpreading.dto.cognition.ConfirmCandidateRequest;
import com.example.httpreading.repository.cognition.ConceptAliasRepository;
import com.example.httpreading.repository.cognition.ConceptCandidateRecordRepository;
import com.example.httpreading.repository.cognition.ConceptMergeRelationRepository;
import com.example.httpreading.repository.cognition.KnowledgeConceptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConceptManagementServiceTest {
    private ConceptCandidateRecordRepository candidateRecordRepository;
    private KnowledgeConceptRepository conceptRepository;
    private ConceptAliasRepository aliasRepository;
    private ConceptMergeRelationRepository mergeRelationRepository;
    private ConceptManagementService managementService;

    @BeforeEach
    void setUp() {
        candidateRecordRepository = org.mockito.Mockito.mock(ConceptCandidateRecordRepository.class);
        conceptRepository = org.mockito.Mockito.mock(KnowledgeConceptRepository.class);
        aliasRepository = org.mockito.Mockito.mock(ConceptAliasRepository.class);
        mergeRelationRepository = org.mockito.Mockito.mock(ConceptMergeRelationRepository.class);
        managementService = new ConceptManagementService(
            candidateRecordRepository,
            conceptRepository,
            aliasRepository,
            mergeRelationRepository,
            new ConceptNormalizationService());
    }

    @Test
    void confirmCandidateCreatesConfirmedConceptWhenNoTargetExists() {
        ConceptCandidateRecord record = new ConceptCandidateRecord();
        record.setId(1L);
        record.setEventId("evt-1");
        record.setCandidateName("政治投机");
        record.setConfidence(0.62d);
        record.setStatus(ConceptCandidateStatus.PENDING);
        when(candidateRecordRepository.findById(1L)).thenReturn(Optional.of(record));
        when(conceptRepository.save(any())).thenAnswer(invocation -> {
            KnowledgeConcept concept = invocation.getArgument(0);
            concept.setId(101L);
            return concept;
        });
        when(aliasRepository.existsByConceptIdAndNormalizedAliasName(101L, "政治投机")).thenReturn(false);

        var response = managementService.confirmCandidate(1L, new ConfirmCandidateRequest(null, null, "说明", null));

        assertEquals("confirmed", response.status());
        assertEquals(101L, response.conceptId());
        assertEquals(ConceptStatus.CONFIRMED, response.conceptStatus());
        assertEquals(ConceptCandidateStatus.ACCEPTED, record.getStatus());
        verify(candidateRecordRepository).save(record);
    }

    @Test
    void mergeConceptMarksSourceMergedAndKeepsRelation() {
        KnowledgeConcept source = concept(1L, "政治投机", ConceptStatus.CONFIRMED);
        KnowledgeConcept target = concept(2L, "机会主义", ConceptStatus.CONFIRMED);
        when(conceptRepository.findById(1L)).thenReturn(Optional.of(source));
        when(conceptRepository.findById(2L)).thenReturn(Optional.of(target));

        var response = managementService.mergeConcept(1L, 2L);

        assertEquals("merged", response.status());
        assertEquals(ConceptStatus.MERGED, source.getStatus());
        assertEquals(2L, source.getMergedToConceptId());
        ArgumentCaptor<ConceptMergeRelation> relation = ArgumentCaptor.forClass(ConceptMergeRelation.class);
        verify(mergeRelationRepository).save(relation.capture());
        assertEquals(1L, relation.getValue().getSourceConceptId());
        assertEquals(2L, relation.getValue().getTargetConceptId());
    }

    private KnowledgeConcept concept(Long id, String name, ConceptStatus status) {
        KnowledgeConcept concept = new KnowledgeConcept();
        concept.setId(id);
        concept.setCanonicalName(name);
        concept.setNormalizedName(name);
        concept.setStatus(status);
        return concept;
    }
}
