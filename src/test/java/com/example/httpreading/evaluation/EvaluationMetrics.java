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

    record RequiredItemCheck(String item, boolean matched, String reason) {
        RequiredItemCheck {
            item = item == null ? "" : item;
            reason = reason == null ? "" : reason;
        }
    }

    record ForbiddenItemCheck(String item, boolean hit, String severity, String reason) {
        ForbiddenItemCheck {
            item = item == null ? "" : item;
            severity = normalizeSeverity(severity);
            reason = reason == null ? "" : reason;
        }
    }

    record StyleConstraintCheck(String item, boolean matched, String reason) {
        StyleConstraintCheck {
            item = item == null ? "" : item;
            reason = reason == null ? "" : reason;
        }
    }

    record JudgeScore(List<CriterionScore> criterionScores, List<String> policyViolations,
                      List<RequiredItemCheck> requiredItemChecks,
                      List<ForbiddenItemCheck> forbiddenItemChecks,
                      List<StyleConstraintCheck> styleConstraintChecks,
                      String feedback, boolean scored, boolean forceZero) {
        JudgeScore {
            criterionScores = criterionScores == null ? List.of() : List.copyOf(criterionScores);
            policyViolations = policyViolations == null ? List.of() : List.copyOf(policyViolations);
            requiredItemChecks = requiredItemChecks == null ? List.of() : List.copyOf(requiredItemChecks);
            forbiddenItemChecks = forbiddenItemChecks == null ? List.of() : List.copyOf(forbiddenItemChecks);
            styleConstraintChecks = styleConstraintChecks == null ? List.of() : List.copyOf(styleConstraintChecks);
            feedback = feedback == null ? "" : feedback;
        }

        JudgeScore(List<CriterionScore> criterionScores, List<String> policyViolations,
                   String feedback, boolean scored) {
            this(criterionScores, policyViolations, List.of(), List.of(), List.of(), feedback, scored, false);
        }

        static JudgeScore unscored(String feedback) {
            return new JudgeScore(List.of(), List.of("judge_unscored"), List.of(), List.of(), List.of(),
                feedback, false, false);
        }

        double normalizedScore() {
            double maximum = criterionScores.stream().mapToDouble(CriterionScore::maxScore).sum();
            return maximum <= 0 ? 0.0
                : criterionScores.stream().mapToDouble(CriterionScore::score).sum() / maximum;
        }

        List<String> missingRequiredItems() {
            return requiredItemChecks.stream().filter(check -> !check.matched()).map(RequiredItemCheck::item).toList();
        }

        List<String> forbiddenItemsHit() {
            return forbiddenItemChecks.stream().filter(ForbiddenItemCheck::hit).map(ForbiddenItemCheck::item).toList();
        }

        List<String> styleViolations() {
            return styleConstraintChecks.stream().filter(check -> !check.matched()).map(StyleConstraintCheck::item).toList();
        }

        double requiredItemRecall(EvaluationCases.ExpectedBehavior expected) {
            int total = expected.mustInclude().size();
            return total == 0 ? 1.0 : (double) (total - missingRequiredItems().size()) / total;
        }

        double forbiddenItemHitRate(EvaluationCases.ExpectedBehavior expected) {
            int total = expected.mustNotInclude().size();
            return total == 0 ? 0.0 : (double) forbiddenItemsHit().size() / total;
        }

        double styleCompliance(EvaluationCases.ExpectedBehavior expected) {
            int total = expected.styleConstraints().size();
            return total == 0 ? 1.0 : (double) (total - styleViolations().size()) / total;
        }

        boolean hasHighSeverityForbiddenHit() {
            return forbiddenItemChecks.stream().anyMatch(check -> check.hit() && "high".equals(check.severity()));
        }
    }

    record AnswerScore(RuleScore rules, JudgeScore judge, double criterionScore, double requiredItemRecall,
                       double forbiddenItemHitRate, double styleCompliance, double overall) {
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
    static AnswerScore combineAnswer(EvaluationCases.ExpectedBehavior expected, RuleScore rules, JudgeScore judge) {
        if (!judge.scored() || !rules.outputNonBlank()) {
            return new AnswerScore(rules, judge, 0.0, 0.0, 0.0, 0.0, 0.0);
        }
        double criterion = judge.normalizedScore();
        double requiredRecall = judge.requiredItemRecall(expected);
        double forbiddenHitRate = judge.forbiddenItemHitRate(expected);
        double styleCompliance = judge.styleCompliance(expected);
        double overall = criterion
            - 0.15 * judge.missingRequiredItems().size()
            - 0.10 * judge.styleViolations().size()
            - forbiddenPenalty(judge)
            - rules.lengthPenalty();
        overall = Math.max(0.0, Math.min(1.0, overall));
        if (judge.forceZero()) overall = 0.0;
        if (!judge.policyViolations().isEmpty() || judge.hasHighSeverityForbiddenHit()) {
            overall = Math.min(0.49, overall);
        }
        return new AnswerScore(rules, judge, criterion, requiredRecall, forbiddenHitRate, styleCompliance, overall);
    }

    static AnswerScore combineAnswer(RuleScore rules, JudgeScore judge) {
        EvaluationCases.ExpectedBehavior empty = new EvaluationCases.ExpectedBehavior(
            List.of(), 0, null, List.of(), List.of(), List.of(), "", "", 0);
        return combineAnswer(empty, rules, judge);
    }

    private static double forbiddenPenalty(JudgeScore judge) {
        return judge.forbiddenItemChecks().stream().filter(ForbiddenItemCheck::hit)
            .mapToDouble(check -> switch (check.severity()) {
                case "low" -> 0.05;
                case "medium" -> 0.20;
                case "high" -> 0.50;
                default -> 0.10;
            }).sum();
    }

    private static String normalizeSeverity(String severity) {
        String value = severity == null ? "" : severity.trim().toLowerCase();
        return switch (value) {
            case "low", "medium", "high" -> value;
            default -> "medium";
        };
    }

    private static double lengthPenalty(int actualChars, int maxChars) {
        if (maxChars <= 0 || actualChars <= maxChars) return 0.0;
        return actualChars <= Math.ceil(maxChars * 1.5) ? 0.05 : 0.10;
    }
}
