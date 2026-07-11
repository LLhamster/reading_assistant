package com.example.httpreading.service.cognition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.example.httpreading.domain.cognition.ConceptAlias;
import com.example.httpreading.domain.cognition.ConceptResolutionDecision;
import com.example.httpreading.domain.cognition.ConceptStatus;
import com.example.httpreading.domain.cognition.ConfidenceLevel;
import com.example.httpreading.domain.cognition.KnowledgeConcept;
import com.example.httpreading.dto.cognition.ConceptCandidateDto;
import com.example.httpreading.dto.cognition.ConceptResolutionRequest;
import com.example.httpreading.repository.cognition.ConceptAliasRepository;
import com.example.httpreading.repository.cognition.ConceptCandidateRecordRepository;
import com.example.httpreading.repository.cognition.ConceptMergeRelationRepository;
import com.example.httpreading.repository.cognition.ConceptResolutionRecordRepository;
import com.example.httpreading.repository.cognition.ConceptSourceRepository;
import com.example.httpreading.repository.cognition.KnowledgeConceptRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConceptResolverServiceTest {
    private KnowledgeConceptRepository conceptRepository;
    private ConceptAliasRepository aliasRepository;
    private ConceptResolutionRecordRepository resolutionRepository;
    private ConceptCandidateRecordRepository candidateRecordRepository;
    private ConceptModelAnalyzer modelAnalyzer;
    private ConceptResolverService resolverService;
    private ConceptConfidenceScorer scorer;

    @BeforeEach
    void setUp() {
        ConceptNormalizationService normalizationService = new ConceptNormalizationService();
        conceptRepository = mock(KnowledgeConceptRepository.class);
        aliasRepository = mock(ConceptAliasRepository.class);
        ConceptMergeRelationRepository mergeRelationRepository = mock(ConceptMergeRelationRepository.class);
        ConceptSourceRepository sourceRepository = mock(ConceptSourceRepository.class);
        resolutionRepository = mock(ConceptResolutionRecordRepository.class);
        candidateRecordRepository = mock(ConceptCandidateRecordRepository.class);
        modelAnalyzer = mock(ConceptModelAnalyzer.class);
        ConceptMatcher matcher = new ConceptMatcher(
            conceptRepository,
            aliasRepository,
            mergeRelationRepository,
            normalizationService,
            new NoopConceptSimilarityRecallPort());
        scorer = new ConceptConfidenceScorer(normalizationService);
        resolverService = new ConceptResolverService(
            new ConceptCandidateExtractor(normalizationService),
            matcher,
            modelAnalyzer,
            scorer,
            normalizationService,
            conceptRepository,
            sourceRepository,
            resolutionRepository,
            candidateRecordRepository,
            new ObjectMapper());
        when(resolutionRepository.existsByEventId(any())).thenReturn(false);
        when(conceptRepository.findByNormalizedName(anyString())).thenReturn(List.of());
        when(conceptRepository.findTop10ByNormalizedNameStartingWithOrNormalizedNameEndingWith(anyString(), anyString()))
            .thenReturn(List.of());
        when(aliasRepository.findByNormalizedAliasName(anyString())).thenReturn(List.of());
    }

    @Test
    void selectedTextExactConfirmedConceptLinksExisting() {
        KnowledgeConcept concept = concept(101L, "机会主义", ConceptStatus.CONFIRMED);
        when(conceptRepository.findFirstByCanonicalNameIgnoreCase("机会主义")).thenReturn(Optional.of(concept));
        when(conceptRepository.findByNormalizedName("机会主义")).thenReturn(List.of(concept));
        when(modelAnalyzer.analyze(any(), any())).thenReturn(model(0.90d, 0.90d));

        var result = resolverService.resolve(request("evt-1", "机会主义", "这里的机会主义是什么意思？", "机会主义"));

        assertEquals(ConceptResolutionDecision.LINK_EXISTING, result.decision());
        assertEquals(ConfidenceLevel.HIGH, result.confidenceLevel());
        assertEquals(101L, result.matchedConceptId());
        assertEquals(1.0d, result.scoreBreakdown().lexicalMatchScore());
    }

    @Test
    void questionAliasMapsToCanonicalConcept() {
        KnowledgeConcept concept = concept(101L, "机会主义", ConceptStatus.CONFIRMED);
        ConceptAlias alias = new ConceptAlias();
        alias.setConceptId(101L);
        alias.setAliasName("见风使舵");
        alias.setNormalizedAliasName("见风使舵");
        when(aliasRepository.findFirstByAliasNameIgnoreCase("见风使舵")).thenReturn(Optional.of(alias));
        when(conceptRepository.findById(101L)).thenReturn(Optional.of(concept));
        when(aliasRepository.findByNormalizedAliasName("见风使舵")).thenReturn(List.of(alias));
        when(modelAnalyzer.analyze(any(), any())).thenReturn(model(0.92d, 0.60d));

        var result = resolverService.resolve(request("evt-2", null, "见风使舵是什么意思？", null));

        assertEquals(101L, result.matchedConceptId());
        assertEquals("机会主义", result.primaryConceptName());
        assertEquals(0.85d, result.scoreBreakdown().lexicalMatchScore());
    }

    @Test
    void genericQuestionWithoutAnyContextDoesNotCreateConcept() {
        var result = resolverService.resolve(request("evt-3", null, "这里是什么意思", null));

        assertEquals(ConceptResolutionDecision.NO_CONCEPT, result.decision());
        assertTrue(result.candidateConcepts().isEmpty());
        verify(modelAnalyzer, never()).analyze(any(), any());
        verify(candidateRecordRepository, never()).save(any());
    }

    @Test
    void lowConfidenceDoesNotCreateConfirmedConcept() {
        when(modelAnalyzer.analyze(any(), any())).thenReturn(model(0.0d, 0.0d));

        var result = resolverService.resolve(request("evt-4", "陌生概念", "这里是什么意思？", null));

        assertEquals(ConceptResolutionDecision.NEED_MORE_CONTEXT, result.decision());
        assertEquals(ConfidenceLevel.LOW, result.confidenceLevel());
        verify(conceptRepository, never()).save(any());
        verify(candidateRecordRepository).save(any());
    }

    @Test
    void scorerHandlesCloseCandidateGap() {
        ConceptResolutionRequest request = request("evt-5", null, "政治投机是什么意思？", "政治投机");
        ConceptScore score = scorer.score(
            request,
            "政治投机",
            null,
            List.of(new ConceptCandidateDto(1L, "机会主义", 0.91d), new ConceptCandidateDto(2L, "政治投机", 0.89d)),
            model(0.80d, 0.80d));

        assertEquals(0.0d, score.breakdown().candidateGapScore());
    }

    @Test
    void mergedConceptRedirectsToTargetConcept() {
        KnowledgeConcept source = concept(1L, "政治投机", ConceptStatus.MERGED);
        source.setMergedToConceptId(2L);
        KnowledgeConcept target = concept(2L, "机会主义", ConceptStatus.CONFIRMED);
        when(conceptRepository.findFirstByCanonicalNameIgnoreCase("政治投机")).thenReturn(Optional.of(source));
        when(conceptRepository.findByNormalizedName("政治投机")).thenReturn(List.of(source));
        when(conceptRepository.findById(2L)).thenReturn(Optional.of(target));
        when(modelAnalyzer.analyze(any(), any())).thenReturn(model(0.95d, 0.90d));

        var result = resolverService.resolve(request("evt-6", "政治投机", "政治投机是什么意思？", "政治投机"));

        assertEquals(ConceptResolutionDecision.LINK_EXISTING, result.decision());
        assertEquals(2L, result.matchedConceptId());
        assertEquals("机会主义", result.primaryConceptName());
    }

    private ConceptResolutionRequest request(String eventId, String selectedText, String question, String recentDialogue) {
        return new ConceptResolutionRequest(
            eventId,
            "u1",
            1L,
            1,
            "s1",
            question,
            selectedText,
            null,
            null,
            null,
            recentDialogue);
    }

    private ConceptModelAnalysis model(double modelScore, double contextScore) {
        return new ConceptModelAnalysis(
            "机会主义",
            modelScore,
            contextScore,
            List.of("模型认为上下文支持候选概念"),
            "模型返回概念判断",
            "test-model",
            ConceptModelAnalyzer.PROMPT_VERSION,
            ConceptModelAnalyzer.ANALYZER_VERSION);
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
