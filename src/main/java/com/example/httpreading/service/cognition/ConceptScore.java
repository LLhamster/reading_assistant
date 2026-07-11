package com.example.httpreading.service.cognition;

import java.util.List;

import com.example.httpreading.domain.cognition.ConfidenceLevel;
import com.example.httpreading.dto.cognition.ScoreBreakdownDto;

record ConceptScore(
    double confidence,
    ConfidenceLevel confidenceLevel,
    ScoreBreakdownDto breakdown,
    List<String> evidence
) {
}
