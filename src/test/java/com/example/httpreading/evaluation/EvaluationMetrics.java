package com.example.httpreading.evaluation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class EvaluationMetrics {
    private EvaluationMetrics() {
    }

    record RoutingPrediction(String plannerMode, String plannerServer, List<String> tools) {
        RoutingPrediction {
            plannerMode = plannerMode == null ? "" : plannerMode;
            plannerServer = plannerServer == null ? "" : plannerServer;
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    record ExecutionTrace(List<String> tools, List<String> evidenceIds, int toolCalls, long latencyMs,
                          int modelCalls, long inputChars, long outputChars) {
        ExecutionTrace {
            tools = tools == null ? List.of() : List.copyOf(tools);
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }

        static ExecutionTrace empty() {
            return new ExecutionTrace(List.of(), List.of(), 0, 0L, 0, 0L, 0L);
        }
    }

    record RoutingScore(boolean modeServerCorrect, double toolPrecision, double toolRecall, double toolF1,
                        boolean exactMatch, double overall) {
    }

    record AnswerPrediction(String answer, ExecutionTrace trace) {
        AnswerPrediction {
            answer = answer == null ? "" : answer;
            trace = trace == null ? ExecutionTrace.empty() : trace;
        }
    }

    record RuleScore(boolean outputNonBlank, double lengthPenalty) {
    }

    record CriterionScore(String id, double score, double maxScore, String reason) {
        CriterionScore {
            id = id == null ? "" : id;
            reason = reason == null ? "" : reason;
        }
    }

    record JudgeScore(List<CriterionScore> criterionScores, List<String> policyViolations,
                      String feedback, boolean scored) {
        JudgeScore {
            criterionScores = criterionScores == null ? List.of() : List.copyOf(criterionScores);
            policyViolations = policyViolations == null ? List.of() : List.copyOf(policyViolations);
            feedback = feedback == null ? "" : feedback;
        }

        static JudgeScore unscored(String feedback) {
            return new JudgeScore(List.of(), List.of("judge_unscored"), feedback, false);
        }

        double normalizedScore() {
            double maximum = criterionScores.stream().mapToDouble(CriterionScore::maxScore).sum();
            return maximum <= 0 ? 0.0
                : criterionScores.stream().mapToDouble(CriterionScore::score).sum() / maximum;
        }
    }

    record AnswerScore(RuleScore rules, JudgeScore judge, double overall) {
    }

    static RoutingScore scoreRoute(EvaluationCases.ExpectedResult expected, RoutingPrediction actual) {
        Set<String> gold = new HashSet<>(expected.localTools());
        Set<String> predicted = new HashSet<>(actual.tools());
        int intersection = (int) predicted.stream().filter(gold::contains).count();
        double precision = predicted.isEmpty() ? (gold.isEmpty() ? 1.0 : 0.0) : (double) intersection / predicted.size();
        double recall = gold.isEmpty() ? (predicted.isEmpty() ? 1.0 : 0.0) : (double) intersection / gold.size();
        double f1 = precision + recall == 0.0 ? 0.0 : 2.0 * precision * recall / (precision + recall);
        boolean modeServer = expected.plannerMode().equals(actual.plannerMode())
            && expected.plannerServer().equals(actual.plannerServer());
        boolean exact = modeServer && gold.equals(predicted);
        return new RoutingScore(modeServer, precision, recall, f1, exact,
            0.5 * (modeServer ? 1.0 : 0.0) + 0.5 * f1);
    }

    static RuleScore scoreAnswerRules(EvaluationCases.ExpectedBehavior expected, AnswerPrediction actual) {
        return new RuleScore(!actual.answer().isBlank(), lengthPenalty(actual.answer().length(), expected.maxChars()));
    }

    /** Each case defines its own additive criteria; LLM Judge awards points criterion by criterion. */
    static AnswerScore combineAnswer(RuleScore rules, JudgeScore judge) {
        if (!judge.scored() || !rules.outputNonBlank()) return new AnswerScore(rules, judge, 0.0);
        double overall = judge.normalizedScore() - rules.lengthPenalty();
        if (!judge.policyViolations().isEmpty()) overall = Math.min(0.49, overall);
        return new AnswerScore(rules, judge, Math.max(0.0, Math.min(1.0, overall)));
    }

    private static double lengthPenalty(int actualChars, int maxChars) {
        if (maxChars <= 0 || actualChars <= maxChars) return 0.0;
        return actualChars <= Math.ceil(maxChars * 1.5) ? 0.05 : 0.10;
    }
}
