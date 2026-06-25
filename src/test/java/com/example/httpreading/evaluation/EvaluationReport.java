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
    double criterionScore,
    double requiredItemRecall,
    double forbiddenItemHitRate,
    double styleCompliance,
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
                      double criterionScore, double requiredItemRecall,
                      double forbiddenItemHitRate, double styleCompliance,
                      List<String> missingRequiredItems,
                      List<String> forbiddenItemsHit,
                      List<String> styleViolations,
                      List<EvaluationMetrics.CriterionScore> criterionScores,
                      List<String> policyViolations,
                      String answerShape,
                      String failureMode) {
        CaseResult {
            agentOutput = agentOutput == null ? "" : agentOutput;
            feedback = feedback == null ? "" : feedback;
            missingRequiredItems = missingRequiredItems == null ? List.of() : List.copyOf(missingRequiredItems);
            forbiddenItemsHit = forbiddenItemsHit == null ? List.of() : List.copyOf(forbiddenItemsHit);
            styleViolations = styleViolations == null ? List.of() : List.copyOf(styleViolations);
            criterionScores = criterionScores == null ? List.of() : List.copyOf(criterionScores);
            policyViolations = policyViolations == null ? List.of() : List.copyOf(policyViolations);
            answerShape = answerShape == null ? "" : answerShape;
            failureMode = failureMode == null ? "" : failureMode;
        }
    }
}
