package com.example.httpreading.dto.cognition;

import com.example.httpreading.domain.cognition.ConceptCandidateStatus;
import com.example.httpreading.domain.cognition.ConceptStatus;

public record ConceptManagementResponse(
    String status,
    Long conceptId,
    String conceptName,
    ConceptStatus conceptStatus,
    Long candidateRecordId,
    ConceptCandidateStatus candidateStatus
) {
}
