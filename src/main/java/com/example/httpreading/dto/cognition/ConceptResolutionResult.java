package com.example.httpreading.dto.cognition;

import java.util.List;

import com.example.httpreading.domain.cognition.ConceptResolutionDecision;
import com.example.httpreading.domain.cognition.ConfidenceLevel;

public record ConceptResolutionResult(
    String eventId,
    String primaryConceptName,
    Long matchedConceptId,
    List<ConceptCandidateDto> candidateConcepts,
    double confidence,
    ConfidenceLevel confidenceLevel,
    ConceptResolutionDecision decision,
    ScoreBreakdownDto scoreBreakdown,
    List<String> contextEvidence,
    String reason
) {
}
