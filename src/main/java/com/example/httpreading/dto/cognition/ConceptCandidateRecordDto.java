package com.example.httpreading.dto.cognition;

import java.time.LocalDateTime;

import com.example.httpreading.domain.cognition.ConceptCandidateStatus;

public record ConceptCandidateRecordDto(
    Long id,
    String eventId,
    String candidateName,
    Long matchedConceptId,
    Double confidence,
    Double modelScore,
    Double lexicalScore,
    Double contextScore,
    Double historyScore,
    Double candidateGapScore,
    ConceptCandidateStatus status,
    String reason,
    LocalDateTime createdAt
) {
}
