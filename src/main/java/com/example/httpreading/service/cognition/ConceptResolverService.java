package com.example.httpreading.service.cognition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.example.httpreading.api.ErrorCode;
import com.example.httpreading.domain.cognition.ConceptCandidateRecord;
import com.example.httpreading.domain.cognition.ConceptCandidateStatus;
import com.example.httpreading.domain.cognition.ConceptResolutionDecision;
import com.example.httpreading.domain.cognition.ConceptResolutionRecord;
import com.example.httpreading.domain.cognition.ConceptSource;
import com.example.httpreading.domain.cognition.ConceptSourceType;
import com.example.httpreading.domain.cognition.ConceptStatus;
import com.example.httpreading.domain.cognition.ConfidenceLevel;
import com.example.httpreading.domain.cognition.KnowledgeConcept;
import com.example.httpreading.dto.cognition.ConceptCandidateDto;
import com.example.httpreading.dto.cognition.ConceptResolutionRequest;
import com.example.httpreading.dto.cognition.ConceptResolutionResult;
import com.example.httpreading.dto.cognition.ScoreBreakdownDto;
import com.example.httpreading.mq.cognition.LearningEvent;
import com.example.httpreading.repository.cognition.ConceptCandidateRecordRepository;
import com.example.httpreading.repository.cognition.ConceptResolutionRecordRepository;
import com.example.httpreading.repository.cognition.ConceptSourceRepository;
import com.example.httpreading.repository.cognition.KnowledgeConceptRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConceptResolverService {
    private static final ScoreBreakdownDto ZERO_BREAKDOWN = new ScoreBreakdownDto(0, 0, 0, 0, 0);

    private final ConceptCandidateExtractor candidateExtractor;
    private final ConceptMatcher conceptMatcher;
    private final ConceptModelAnalyzer modelAnalyzer;
    private final ConceptConfidenceScorer confidenceScorer;
    private final ConceptNormalizationService normalizationService;
    private final KnowledgeConceptRepository conceptRepository;
    private final ConceptSourceRepository sourceRepository;
    private final ConceptResolutionRecordRepository resolutionRepository;
    private final ConceptCandidateRecordRepository candidateRecordRepository;
    private final ObjectMapper objectMapper;

    public ConceptResolverService(ConceptCandidateExtractor candidateExtractor,
                                  ConceptMatcher conceptMatcher,
                                  ConceptModelAnalyzer modelAnalyzer,
                                  ConceptConfidenceScorer confidenceScorer,
                                  ConceptNormalizationService normalizationService,
                                  KnowledgeConceptRepository conceptRepository,
                                  ConceptSourceRepository sourceRepository,
                                  ConceptResolutionRecordRepository resolutionRepository,
                                  ConceptCandidateRecordRepository candidateRecordRepository,
                                  ObjectMapper objectMapper) {
        this.candidateExtractor = candidateExtractor;
        this.conceptMatcher = conceptMatcher;
        this.modelAnalyzer = modelAnalyzer;
        this.confidenceScorer = confidenceScorer;
        this.normalizationService = normalizationService;
        this.conceptRepository = conceptRepository;
        this.sourceRepository = sourceRepository;
        this.resolutionRepository = resolutionRepository;
        this.candidateRecordRepository = candidateRecordRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ConceptResolutionResult resolve(LearningEvent event) {
        ConceptResolutionRequest request = toRequest(event);
        return resolve(request);
    }

    @Transactional
    public ConceptResolutionResult resolve(ConceptResolutionRequest request) {
        if (request.eventId() != null && resolutionRepository.existsByEventId(request.eventId())) {
            return getResolution(request.eventId());
        }

        List<ExtractedConceptCandidate> extracted = candidateExtractor.extract(request);
        if (extracted.isEmpty()) {
            ConceptResolutionResult result = new ConceptResolutionResult(
                request.eventId(),
                null,
                null,
                List.of(),
                0.0d,
                ConfidenceLevel.LOW,
                ConceptResolutionDecision.NO_CONCEPT,
                ZERO_BREAKDOWN,
                List.of(),
                "没有可用概念候选，未创建概念");
            saveResolution(request, result, ConceptModelAnalyzer.PROMPT_VERSION, ConceptModelAnalyzer.ANALYZER_VERSION, "");
            return result;
        }

        ExtractedConceptCandidate primary = extracted.stream()
            .max(Comparator.comparingDouble(ExtractedConceptCandidate::priority))
            .orElse(extracted.get(0));
        List<MatchedConcept> matches = conceptMatcher.match(primary.name(), request.bookId());
        List<ConceptCandidateDto> candidateDtos = buildCandidateDtos(primary, extracted, matches);
        MatchedConcept topMatch = matches.isEmpty() ? null : matches.get(0);
        ConceptModelAnalysis modelAnalysis = modelAnalyzer.analyze(request, primary.name());
        ConceptScore score = confidenceScorer.score(request, primary.name(), topMatch, candidateDtos, modelAnalysis);
        ConceptResolutionDecision decision = decide(score, topMatch);

        Long matchedConceptId = topMatch == null ? null : topMatch.concept().getId();
        String primaryConceptName = topMatch == null ? primary.name() : topMatch.concept().getCanonicalName();
        if (decision == ConceptResolutionDecision.CREATE_CANDIDATE && topMatch == null && score.confidenceLevel() == ConfidenceLevel.HIGH) {
            KnowledgeConcept concept = createCandidateConcept(primary.name(), request);
            matchedConceptId = concept.getId();
            primaryConceptName = concept.getCanonicalName();
            candidateDtos = replaceFallbackCandidate(candidateDtos, concept, score.confidence());
        }

        String reason = buildReason(primary, topMatch, modelAnalysis, score, decision);
        ConceptResolutionResult result = new ConceptResolutionResult(
            request.eventId(),
            primaryConceptName,
            matchedConceptId,
            candidateDtos,
            score.confidence(),
            score.confidenceLevel(),
            decision,
            score.breakdown(),
            score.evidence(),
            reason);
        saveResolution(request, result, modelAnalysis.promptVersion(), modelAnalysis.analyzerVersion(), modelAnalysis.modelName());
        saveCandidateRecordIfNeeded(request, result, score, decision);
        if (decision == ConceptResolutionDecision.LINK_EXISTING && matchedConceptId != null) {
            saveSource(matchedConceptId, request, sourceType(primary.source()), primary.name());
        }
        return result;
    }

    @Transactional(readOnly = true)
    public ConceptResolutionResult getResolution(String eventId) {
        ConceptResolutionRecord record = resolutionRepository.findByEventId(eventId)
            .orElseThrow(() -> ErrorCode.RESOURCE_NOT_FOUND.toException("概念解析结果不存在: " + eventId));
        return fromRecord(record);
    }

    private ConceptResolutionRequest toRequest(LearningEvent event) {
        return new ConceptResolutionRequest(
            event.getEventId(),
            event.getUserId(),
            event.getBookId(),
            event.getChapterIndex(),
            event.getSessionId(),
            event.getQuestion(),
            event.getSelectedText(),
            event.getSelectedContext(),
            event.getChapterTitle(),
            event.getChapterContent(),
            event.getRecentDialogueSummary());
    }

    private List<ConceptCandidateDto> buildCandidateDtos(ExtractedConceptCandidate primary,
                                                         List<ExtractedConceptCandidate> extracted,
                                                         List<MatchedConcept> matches) {
        List<ConceptCandidateDto> candidateDtos = new ArrayList<>(conceptMatcher.toCandidateDtos(matches, primary.name()));
        for (ExtractedConceptCandidate candidate : extracted) {
            boolean exists = candidateDtos.stream()
                .anyMatch(dto -> normalizationService.normalize(dto.name()).equals(normalizationService.normalize(candidate.name())));
            if (!exists) {
                candidateDtos.add(new ConceptCandidateDto(null, candidate.name(), Math.max(0.20d, candidate.priority() * 0.4d)));
            }
            if (candidateDtos.size() >= 5) {
                break;
            }
        }
        return candidateDtos.stream()
            .sorted(Comparator.comparingDouble(ConceptCandidateDto::score).reversed())
            .limit(5)
            .toList();
    }

    private ConceptResolutionDecision decide(ConceptScore score, MatchedConcept topMatch) {
        if (score.confidenceLevel() == ConfidenceLevel.HIGH) {
            if (topMatch != null && topMatch.concept().getStatus() == ConceptStatus.CONFIRMED) {
                return ConceptResolutionDecision.LINK_EXISTING;
            }
            return ConceptResolutionDecision.CREATE_CANDIDATE;
        }
        if (score.confidenceLevel() == ConfidenceLevel.MEDIUM) {
            return ConceptResolutionDecision.CREATE_CANDIDATE;
        }
        return ConceptResolutionDecision.NEED_MORE_CONTEXT;
    }

    private KnowledgeConcept createCandidateConcept(String name, ConceptResolutionRequest request) {
        KnowledgeConcept concept = new KnowledgeConcept();
        concept.setCanonicalName(name.trim());
        concept.setNormalizedName(normalizationService.normalize(name));
        concept.setBookId(request.bookId());
        concept.setFirstChapterIndex(request.chapterIndex());
        concept.setStatus(ConceptStatus.CANDIDATE);
        KnowledgeConcept saved = conceptRepository.save(concept);
        saveSource(saved.getId(), request, ConceptSourceType.MODEL_EXTRACTED, name);
        return saved;
    }

    private void saveSource(Long conceptId, ConceptResolutionRequest request, ConceptSourceType sourceType, String sourceText) {
        ConceptSource source = new ConceptSource();
        source.setConceptId(conceptId);
        source.setBookId(request.bookId());
        source.setChapterIndex(request.chapterIndex());
        source.setSourceType(sourceType);
        source.setSourceText(sourceText);
        source.setSourceRef(request.eventId());
        sourceRepository.save(source);
    }

    private ConceptSourceType sourceType(String source) {
        if ("selectedText".equals(source)) {
            return ConceptSourceType.SELECTED_TEXT;
        }
        if ("question".equals(source)) {
            return ConceptSourceType.USER_QUESTION;
        }
        if ("chapterContent".equals(source) || "chapterTitle".equals(source)) {
            return ConceptSourceType.CHAPTER_CONTENT;
        }
        return ConceptSourceType.MODEL_EXTRACTED;
    }

    private List<ConceptCandidateDto> replaceFallbackCandidate(List<ConceptCandidateDto> candidateDtos,
                                                               KnowledgeConcept concept,
                                                               double score) {
        List<ConceptCandidateDto> result = new ArrayList<>();
        result.add(new ConceptCandidateDto(concept.getId(), concept.getCanonicalName(), score));
        for (ConceptCandidateDto dto : candidateDtos) {
            if (!normalizationService.normalize(dto.name()).equals(concept.getNormalizedName())) {
                result.add(dto);
            }
        }
        return result.stream().limit(5).toList();
    }

    private String buildReason(ExtractedConceptCandidate primary,
                               MatchedConcept topMatch,
                               ConceptModelAnalysis modelAnalysis,
                               ConceptScore score,
                               ConceptResolutionDecision decision) {
        String matchReason = topMatch == null ? "未匹配已有正式概念" : topMatch.reason();
        return "候选来源=" + primary.source()
            + "；匹配=" + matchReason
            + "；模型=" + modelAnalysis.reason()
            + "；综合置信度=" + score.confidence()
            + "；决策=" + decision;
    }

    private void saveCandidateRecordIfNeeded(ConceptResolutionRequest request,
                                             ConceptResolutionResult result,
                                             ConceptScore score,
                                             ConceptResolutionDecision decision) {
        if (decision == ConceptResolutionDecision.NO_CONCEPT || decision == ConceptResolutionDecision.LINK_EXISTING) {
            return;
        }
        ConceptCandidateRecord record = new ConceptCandidateRecord();
        record.setEventId(request.eventId());
        record.setCandidateName(result.primaryConceptName());
        record.setMatchedConceptId(result.matchedConceptId());
        record.setConfidence(result.confidence());
        record.setModelScore(score.breakdown().modelScore());
        record.setLexicalScore(score.breakdown().lexicalMatchScore());
        record.setContextScore(score.breakdown().contextSupportScore());
        record.setHistoryScore(score.breakdown().historyConsistencyScore());
        record.setCandidateGapScore(score.breakdown().candidateGapScore());
        record.setStatus(ConceptCandidateStatus.PENDING);
        record.setReason(result.reason());
        candidateRecordRepository.save(record);
    }

    private void saveResolution(ConceptResolutionRequest request,
                                ConceptResolutionResult result,
                                String promptVersion,
                                String analyzerVersion,
                                String modelName) {
        ConceptResolutionRecord record = new ConceptResolutionRecord();
        record.setEventId(request.eventId());
        record.setUserId(request.userId());
        record.setBookId(request.bookId());
        record.setChapterIndex(request.chapterIndex());
        record.setSessionId(request.sessionId());
        record.setPrimaryConceptName(result.primaryConceptName());
        record.setMatchedConceptId(result.matchedConceptId());
        record.setCandidateConceptsJson(writeJson(result.candidateConcepts()));
        record.setConfidence(result.confidence());
        record.setConfidenceLevel(result.confidenceLevel());
        record.setDecision(result.decision());
        record.setScoreBreakdownJson(writeJson(result.scoreBreakdown()));
        record.setContextEvidenceJson(writeJson(result.contextEvidence()));
        record.setReason(result.reason());
        record.setQuestion(request.question());
        record.setSelectedText(request.selectedText());
        record.setSelectedContext(request.selectedContext());
        record.setRecentDialogueSummary(request.recentDialogue());
        record.setModelName(modelName);
        record.setPromptVersion(promptVersion);
        record.setAnalyzerVersion(analyzerVersion);
        resolutionRepository.save(record);
    }

    private ConceptResolutionResult fromRecord(ConceptResolutionRecord record) {
        return new ConceptResolutionResult(
            record.getEventId(),
            record.getPrimaryConceptName(),
            record.getMatchedConceptId(),
            readCandidates(record.getCandidateConceptsJson()),
            record.getConfidence() == null ? 0.0d : record.getConfidence(),
            record.getConfidenceLevel(),
            record.getDecision(),
            readBreakdown(record.getScoreBreakdownJson()),
            readEvidence(record.getContextEvidenceJson()),
            record.getReason());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "[]";
        }
    }

    private List<ConceptCandidateDto> readCandidates(String json) {
        try {
            if (json == null || json.isBlank()) {
                return List.of();
            }
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception exception) {
            return List.of();
        }
    }

    private ScoreBreakdownDto readBreakdown(String json) {
        try {
            if (json == null || json.isBlank()) {
                return ZERO_BREAKDOWN;
            }
            return objectMapper.readValue(json, ScoreBreakdownDto.class);
        } catch (Exception exception) {
            return ZERO_BREAKDOWN;
        }
    }

    private List<String> readEvidence(String json) {
        try {
            if (json == null || json.isBlank()) {
                return List.of();
            }
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception exception) {
            return List.of();
        }
    }
}
