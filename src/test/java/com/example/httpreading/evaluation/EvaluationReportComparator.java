package com.example.httpreading.evaluation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class EvaluationReportComparator {
    record Comparison(double baselineScore, double candidateScore, double improvement,
                      Map<String, Double> categoryImprovement) {
    }

    Comparison compare(EvaluationReport baseline, EvaluationReport candidate) {
        Set<String> baselineIds = baseline.cases().stream().map(EvaluationReport.CaseResult::id).collect(Collectors.toSet());
        Set<String> candidateIds = candidate.cases().stream().map(EvaluationReport.CaseResult::id).collect(Collectors.toSet());
        if (!baselineIds.equals(candidateIds)) {
            throw new IllegalArgumentException("baseline and candidate must use identical case IDs");
        }
        if (!baseline.datasetFingerprint().equals(candidate.datasetFingerprint())
            || !baseline.split().equals(candidate.split())) {
            throw new IllegalArgumentException("baseline and candidate must use identical dataset and split");
        }
        Map<String, Double> categories = new LinkedHashMap<>();
        Set<String> names = new java.util.TreeSet<>(baseline.categoryScores().keySet());
        names.addAll(candidate.categoryScores().keySet());
        for (String name : names) {
            categories.put(name, candidate.categoryScores().getOrDefault(name, 0.0)
                - baseline.categoryScores().getOrDefault(name, 0.0));
        }
        return new Comparison(baseline.score(), candidate.score(), candidate.score() - baseline.score(), categories);
    }
}
