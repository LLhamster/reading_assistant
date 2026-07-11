package com.example.httpreading.dto.cognition;

public record ScoreBreakdownDto(
    double modelScore,
    double lexicalMatchScore,
    double contextSupportScore,
    double historyConsistencyScore,
    double candidateGapScore
) {
}
