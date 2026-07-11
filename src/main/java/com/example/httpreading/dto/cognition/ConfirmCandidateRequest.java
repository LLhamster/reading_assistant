package com.example.httpreading.dto.cognition;

public record ConfirmCandidateRequest(
    Long targetConceptId,
    String aliasName,
    String description,
    String reason
) {
}
