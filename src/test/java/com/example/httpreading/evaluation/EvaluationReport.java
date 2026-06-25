package com.example.httpreading.evaluation;

import java.util.List;
import java.util.Map;

record EvaluationReport(
    String runId,
    String suite,
    String target,
    String split,
    String evaluationMode,
    String model,
    String datasetFingerprint,
    int numDev,
    int numHoldout,
    int evaluated,
    int passed,
    int unscored,
    double score,
    double modeAccuracy,
    double toolPrecision,
    double toolRecall,
    double toolF1,
    double exactMatch,
    double evidenceRecall,
    long modelCalls,
    long inputChars,
    long outputChars,
    long latencyMs,
    Double estimatedCost,
    List<CaseResult> cases,
    Map<String, Double> categoryScores) {
    EvaluationReport {
        cases = cases == null ? List.of() : List.copyOf(cases);
        categoryScores = categoryScores == null ? Map.of() : Map.copyOf(categoryScores);
    }

    record CaseResult(String id, String category, double score, boolean passed, boolean hardPass,
                      boolean scored, String agentOutput, String feedback,
                      List<EvaluationMetrics.CriterionScore> criterionScores,
                      List<String> policyViolations) {
        CaseResult {
            agentOutput = agentOutput == null ? "" : agentOutput;
            feedback = feedback == null ? "" : feedback;
            criterionScores = criterionScores == null ? List.of() : List.copyOf(criterionScores);
            policyViolations = policyViolations == null ? List.of() : List.copyOf(policyViolations);
        }
    }
}
