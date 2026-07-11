package com.example.httpreading.service.cognition;

import java.util.ArrayList;
import java.util.List;

import com.example.httpreading.domain.cognition.ConfidenceLevel;
import com.example.httpreading.domain.cognition.KnowledgeConcept;
import com.example.httpreading.dto.cognition.ConceptCandidateDto;
import com.example.httpreading.dto.cognition.ConceptResolutionRequest;
import com.example.httpreading.dto.cognition.ScoreBreakdownDto;
import org.springframework.stereotype.Service;

@Service
public class ConceptConfidenceScorer {
    private final ConceptNormalizationService normalizationService;

    public ConceptConfidenceScorer(ConceptNormalizationService normalizationService) {
        this.normalizationService = normalizationService;
    }

    public ConceptScore score(ConceptResolutionRequest request,
                              String candidateName,
                              MatchedConcept matchedConcept,
                              List<ConceptCandidateDto> candidates,
                              ConceptModelAnalysis modelAnalysis) {
        double modelScore = modelAnalysis == null ? 0.0d : clamp(modelAnalysis.modelScore());
        double lexicalScore = lexicalScore(request, candidateName, matchedConcept);
        ContextSupport contextSupport = contextSupport(request, candidateName, modelAnalysis);
        double historyScore = historyScore(request.recentDialogue(), candidateName);
        double gapScore = candidateGapScore(candidates);
        double confidence = clamp(
            0.35d * modelScore
                + 0.25d * lexicalScore
                + 0.20d * contextSupport.score()
                + 0.10d * historyScore
                + 0.10d * gapScore);
        ConfidenceLevel level = confidence >= 0.80d
            ? ConfidenceLevel.HIGH
            : confidence >= 0.60d ? ConfidenceLevel.MEDIUM : ConfidenceLevel.LOW;
        return new ConceptScore(
            round(confidence),
            level,
            new ScoreBreakdownDto(
                round(modelScore),
                round(lexicalScore),
                round(contextSupport.score()),
                round(historyScore),
                round(gapScore)),
            contextSupport.evidence());
    }

    private double lexicalScore(ConceptResolutionRequest request, String candidateName, MatchedConcept matchedConcept) {
        if (matchedConcept == null) {
            String selected = normalizationService.normalize(request.selectedText());
            String candidate = normalizationService.normalize(candidateName);
            if (!selected.isBlank() && selected.equals(candidate)) {
                return 0.75d;
            }
            return 0.0d;
        }
        KnowledgeConcept concept = matchedConcept.concept();
        String canonical = normalizationService.normalize(concept.getCanonicalName());
        String selected = normalizationService.normalize(request.selectedText());
        String question = normalizationService.normalize(request.question());
        String candidate = normalizationService.normalize(candidateName);
        if (!selected.isBlank() && selected.equals(canonical)) {
            return 1.0d;
        }
        if (!question.isBlank() && question.equals(canonical)) {
            return 0.9d;
        }
        if (matchedConcept.aliasMatch()) {
            return 0.85d;
        }
        if (!candidate.isBlank() && candidate.equals(canonical)) {
            return 0.88d;
        }
        if (matchedConcept.score() >= 0.35d) {
            return 0.5d;
        }
        return 0.0d;
    }

    private ContextSupport contextSupport(ConceptResolutionRequest request,
                                          String candidateName,
                                          ConceptModelAnalysis modelAnalysis) {
        List<String> evidence = new ArrayList<>();
        double score = 0.0d;
        String normalized = normalizationService.normalize(candidateName);
        score += supportFrom("当前划词", request.selectedText(), normalized, evidence, 0.90d);
        score += supportFrom("划词上下文", request.selectedContext(), normalized, evidence, 0.30d);
        score += supportFrom("章节标题", request.chapterTitle(), normalized, evidence, 0.20d);
        score += supportFrom("章节内容", request.chapterContent(), normalized, evidence, 0.20d);
        score += supportFrom("最近对话", request.recentDialogue(), normalized, evidence, 0.20d);
        if (modelAnalysis != null && modelAnalysis.contextScore() > score) {
            score = modelAnalysis.contextScore();
            evidence.addAll(modelAnalysis.evidence());
        }
        if (evidence.isEmpty() && modelAnalysis != null) {
            evidence.addAll(modelAnalysis.evidence());
        }
        return new ContextSupport(clamp(score), evidence.stream().distinct().limit(8).toList());
    }

    private double supportFrom(String label, String text, String normalizedCandidate, List<String> evidence, double score) {
        if (normalizationService.isBlank(text) || normalizedCandidate.isBlank()) {
            return 0.0d;
        }
        String normalizedText = normalizationService.normalize(text);
        if (normalizedText.equals(normalizedCandidate)) {
            evidence.add(label + "与候选概念一致");
            return score;
        }
        if (normalizedText.length() <= 80 && normalizedText.startsWith(normalizedCandidate)) {
            evidence.add(label + "以候选概念开头");
            return score * 0.8d;
        }
        if (normalizedText.length() <= 160 && normalizedText.indexOf(normalizedCandidate) >= 0) {
            evidence.add(label + "出现候选概念");
            return score * 0.7d;
        }
        return 0.0d;
    }

    private double historyScore(String recentDialogue, String candidateName) {
        if (normalizationService.isBlank(recentDialogue)) {
            return 0.0d;
        }
        String normalizedHistory = normalizationService.normalize(recentDialogue);
        String normalizedCandidate = normalizationService.normalize(candidateName);
        if (normalizedCandidate.isBlank()) {
            return 0.0d;
        }
        int count = countOccurrences(normalizedHistory, normalizedCandidate);
        if (count >= 3) {
            return 1.0d;
        }
        if (count >= 1) {
            return 0.8d;
        }
        return 0.0d;
    }

    private int countOccurrences(String text, String term) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(term, index)) >= 0) {
            count++;
            index += Math.max(1, term.length());
        }
        return count;
    }

    private double candidateGapScore(List<ConceptCandidateDto> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return 0.0d;
        }
        if (candidates.size() == 1) {
            return 0.8d;
        }
        double gap = candidates.get(0).score() - candidates.get(1).score();
        if (gap >= 0.50d) {
            return 1.0d;
        }
        if (gap >= 0.30d) {
            return 0.8d;
        }
        if (gap >= 0.15d) {
            return 0.5d;
        }
        if (gap >= 0.05d) {
            return 0.2d;
        }
        return 0.0d;
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private double round(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    private record ContextSupport(double score, List<String> evidence) {
    }
}
