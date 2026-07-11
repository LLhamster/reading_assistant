package com.example.httpreading.service.cognition;

import java.util.List;

record ConceptModelAnalysis(
    String concept,
    double modelScore,
    double contextScore,
    List<String> evidence,
    String reason,
    String modelName,
    String promptVersion,
    String analyzerVersion
) {
}
