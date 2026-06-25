package com.example.httpreading.evaluation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.example.httpreading.service.ModelClientException;
import com.fasterxml.jackson.databind.ObjectMapper;

final class EvaluationReplayRunner {
    interface AgentAdapter {
        AgentResult run(EvaluationCases.EvaluationExample example) throws Exception;
    }

    record AgentResult(EvaluationMetrics.RoutingPrediction route, String answer,
                       EvaluationMetrics.ExecutionTrace trace) {
    }

    private final ObjectMapper objectMapper;

    EvaluationReplayRunner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    EvaluationReport run(List<EvaluationCases.EvaluationExample> all,
                         String suite,
                         String split,
                         String target,
                         String model,
                         EvaluationJudge.Mode mode,
                         AgentAdapter adapter,
                         EvaluationJudge judge) {
        return run(all, suite, split, target, model, mode, Integer.MAX_VALUE, adapter, judge);
    }

    EvaluationReport run(List<EvaluationCases.EvaluationExample> all,
                         String suite,
                         String split,
                         String target,
                         String model,
                         EvaluationJudge.Mode mode,
                         int limit,
                         AgentAdapter adapter,
                         EvaluationJudge judge) {
        List<EvaluationCases.EvaluationExample> selected = all.stream()
            .filter(example -> suite.equals(example.suite())
                && (EvaluationCases.ALL.equals(split) || split.equals(example.split())))
            .limit(Math.max(1, limit)).toList();
        List<EvaluationReport.CaseResult> results = new ArrayList<>();
        Aggregate aggregate = new Aggregate();
        long caseDelayMs = Long.parseLong(System.getProperty("evaluation.caseDelayMs", "0"));
        long overloadCooldownMs = Long.parseLong(System.getProperty("evaluation.overloadCooldownMs", "0"));
        boolean stopOnModelOverload = Boolean.getBoolean("evaluation.stopOnModelOverload");
        for (int index = 0; index < selected.size(); index++) {
            EvaluationCases.EvaluationExample example = selected.get(index);
            try {
                AgentResult result = adapter.run(example);
                if (EvaluationCases.TOOL_ROUTING.equals(suite)) {
                    scoreRoute(example, result, results, aggregate);
                } else {
                    scoreAnswer(example, result, judge, mode, results, aggregate);
                }
            } catch (Exception exception) {
                boolean modelOverload = isModelOverload(exception);
                results.add(new EvaluationReport.CaseResult(example.id(), example.category(), 0.0,
                    false, false, false, "", "execution failed: " + exception.getMessage(),
                    0.0, 0.0, 0.0, 0.0, List.of(), List.of(), List.of(), List.of(), List.of(),
                    answerShape(example), failureMode(example)));
                aggregate.addFailure();
                if (modelOverload) {
                    delay(overloadCooldownMs, "model overload cooldown interrupted");
                    if (stopOnModelOverload) {
                        break;
                    }
                }
            }
            delayBetweenCases(caseDelayMs, index, selected.size());
        }
        long unscored = results.stream().filter(result -> !result.scored()).count();
        if (Boolean.getBoolean("evaluation.failOnUnscored")
            && !results.isEmpty() && (double) unscored / results.size() > 0.05) {
            throw new IllegalStateException("unscored cases exceed 5%: " + unscored + "/" + results.size());
        }
        Map<String, Double> categoryScores = results.stream().collect(Collectors.groupingBy(
            EvaluationReport.CaseResult::category, LinkedHashMap::new,
            Collectors.averagingDouble(EvaluationReport.CaseResult::score)));
        int dev = (int) all.stream().filter(example -> suite.equals(example.suite()) && EvaluationCases.DEV.equals(example.split())).count();
        int holdout = (int) all.stream().filter(example -> suite.equals(example.suite()) && EvaluationCases.HOLDOUT.equals(example.split())).count();
        return aggregate.report(suite, split, target, model, mode, dev, holdout, results, categoryScores,
            fingerprint(selected));
    }

    private void scoreRoute(EvaluationCases.EvaluationExample example, AgentResult result,
                            List<EvaluationReport.CaseResult> results, Aggregate aggregate) {
        EvaluationMetrics.RoutingScore score = EvaluationMetrics.scoreRoute(
            example.expectedResult(), result.route());
        boolean passed = score.overall() >= 0.75;
        results.add(new EvaluationReport.CaseResult(example.id(), example.category(), score.overall(),
            passed, true, true, "", passed ? "" : "routing result mismatch",
            0.0, 0.0, 0.0, 0.0, List.of(), List.of(), List.of(), List.of(), List.of(), "", ""));
        aggregate.addRoute(score, result.trace());
    }

    private void scoreAnswer(EvaluationCases.EvaluationExample example, AgentResult result,
                             EvaluationJudge judge, EvaluationJudge.Mode mode,
                             List<EvaluationReport.CaseResult> results, Aggregate aggregate) {
        EvaluationMetrics.AnswerPrediction prediction = new EvaluationMetrics.AnswerPrediction(result.answer(), result.trace());
        EvaluationMetrics.RuleScore rules = EvaluationMetrics.scoreAnswerRules(example.expectedBehavior(), prediction);
        EvaluationMetrics.JudgeScore judged = judge.judge(example, prediction, rules, mode);
        EvaluationMetrics.AnswerScore score = EvaluationMetrics.combineAnswer(example.expectedBehavior(), rules, judged);
        boolean hardPass = rules.outputNonBlank() && judged.policyViolations().isEmpty() && !judged.hasHighSeverityForbiddenHit();
        boolean passed = score.overall() >= 0.75 && hardPass && judged.scored();
        results.add(new EvaluationReport.CaseResult(example.id(), example.category(), score.overall(), passed,
            hardPass, judged.scored(), result.answer(), judged.feedback(),
            score.criterionScore(), score.requiredItemRecall(), score.forbiddenItemHitRate(), score.styleCompliance(),
            judged.missingRequiredItems(), judged.forbiddenItemsHit(), judged.styleViolations(),
            judged.criterionScores(), judged.policyViolations(), answerShape(example), failureMode(example)));
        aggregate.addAnswer(score, result.trace());
    }

    private String answerShape(EvaluationCases.EvaluationExample example) {
        return example.expectedBehavior() == null ? "" : example.expectedBehavior().answerShape();
    }

    private String failureMode(EvaluationCases.EvaluationExample example) {
        return example.expectedBehavior() == null ? "" : example.expectedBehavior().failureMode();
    }

    private String fingerprint(List<EvaluationCases.EvaluationExample> examples) {
        try {
            String canonical = examples.stream().sorted(java.util.Comparator.comparing(EvaluationCases.EvaluationExample::id))
                .map(this::json).collect(Collectors.joining("\n"));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot fingerprint dataset", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void delayBetweenCases(long delayMs, int index, int total) {
        if (delayMs <= 0 || index >= total - 1) {
            return;
        }
        delay(delayMs, "evaluation case delay interrupted");
    }

    private void delay(long delayMs, String interruptedMessage) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interruptedMessage, exception);
        }
    }

    private boolean isModelOverload(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ModelClientException modelClientException
                && modelClientException.statusCode() == 429) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && (message.contains("429") || message.contains("engine_overloaded_error"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class Aggregate {
        double score;
        double mode;
        double precision;
        double recall;
        double f1;
        double exact;
        double evidence;
        double criterionScore;
        double requiredItemRecall;
        double forbiddenItemHitRate;
        double styleCompliance;
        long modelCalls;
        long inputChars;
        long outputChars;
        long latency;
        int count;

        void addRoute(EvaluationMetrics.RoutingScore route, EvaluationMetrics.ExecutionTrace trace) {
            score += route.overall();
            mode += route.modeServerCorrect() ? 1 : 0;
            precision += route.toolPrecision();
            recall += route.toolRecall();
            f1 += route.toolF1();
            exact += route.exactMatch() ? 1 : 0;
            addTrace(trace);
        }

        void addAnswer(EvaluationMetrics.AnswerScore answer, EvaluationMetrics.ExecutionTrace trace) {
            score += answer.overall();
            criterionScore += answer.criterionScore();
            requiredItemRecall += answer.requiredItemRecall();
            forbiddenItemHitRate += answer.forbiddenItemHitRate();
            styleCompliance += answer.styleCompliance();
            addTrace(trace);
        }

        void addTrace(EvaluationMetrics.ExecutionTrace trace) {
            count++;
            modelCalls += trace.modelCalls();
            inputChars += trace.inputChars();
            outputChars += trace.outputChars();
            latency += trace.latencyMs();
        }

        void addFailure() {
            count++;
        }

        EvaluationReport report(String suite, String split, String target, String model, EvaluationJudge.Mode modeName,
                                int dev, int holdout, List<EvaluationReport.CaseResult> results,
                                Map<String, Double> categories, String fingerprint) {
            int passed = (int) results.stream().filter(EvaluationReport.CaseResult::passed).count();
            int unscored = (int) results.stream().filter(result -> !result.scored()).count();
            Double estimatedCost = estimatedCost(inputChars, outputChars);
            return new EvaluationReport(Instant.now().toString().replace(':', '-'), suite, target, split,
                modeName.name(), model, fingerprint, dev, holdout, results.size(), passed, unscored,
                avg(score), avg(mode), avg(precision), avg(recall), avg(f1), avg(exact), avg(evidence),
                avg(criterionScore), avg(requiredItemRecall), avg(forbiddenItemHitRate), avg(styleCompliance),
                modelCalls, inputChars, outputChars, latency, estimatedCost, results, categories);
        }

        double avg(double value) {
            return count == 0 ? 0.0 : value / count;
        }

        Double estimatedCost(long input, long output) {
            String inputRate = System.getProperty("evaluation.cost.inputPerMillion", "");
            String outputRate = System.getProperty("evaluation.cost.outputPerMillion", "");
            if (inputRate.isBlank() || outputRate.isBlank()) {
                return null;
            }
            return input / 1_000_000.0 * Double.parseDouble(inputRate)
                + output / 1_000_000.0 * Double.parseDouble(outputRate);
        }
    }
}
