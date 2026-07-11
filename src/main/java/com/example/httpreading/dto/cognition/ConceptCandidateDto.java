package com.example.httpreading.dto.cognition;

public record ConceptCandidateDto(
    Long conceptId,
    String name,
    double score
) {
}
