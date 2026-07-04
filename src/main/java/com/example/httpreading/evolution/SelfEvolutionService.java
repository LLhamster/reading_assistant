package com.example.httpreading.evolution;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

@Service
public class SelfEvolutionService {
    private final MisunderstandingMemoryMiner miner;
    private final EvalCaseGenerator caseGenerator;
    private final InProcessAgentEvaluator evaluator;
    private final RuleBasedJudge judge;
    private final CandidatePromptGenerator promptGenerator;

    public SelfEvolutionService(MisunderstandingMemoryMiner miner,
                                EvalCaseGenerator caseGenerator,
                                InProcessAgentEvaluator evaluator,
                                RuleBasedJudge judge,
                                CandidatePromptGenerator promptGenerator) {
        this.miner = miner;
        this.caseGenerator = caseGenerator;
        this.evaluator = evaluator;
        this.judge = judge;
        this.promptGenerator = promptGenerator;
    }

    public SelfEvolutionReport run(String userId,
                                   int memoryLimit,
                                   Long defaultBookId,
                                   Integer defaultChapterIndex) {
        return run(userId, memoryLimit, defaultBookId, defaultChapterIndex,
            SelfEvolutionRunOptions.defaults());
    }

    public SelfEvolutionReport run(String userId,
                                   int memoryLimit,
                                   Long defaultBookId,
                                   Integer defaultChapterIndex,
                                   int caseCount) {
        return run(userId, memoryLimit, defaultBookId, defaultChapterIndex,
            SelfEvolutionRunOptions.forCaseCount(caseCount));
    }

    public SelfEvolutionReport run(String userId,
                                   int memoryLimit,
                                   Long defaultBookId,
                                   Integer defaultChapterIndex,
                                   SelfEvolutionRunOptions options) {
        SelfEvolutionRunOptions resolved = options == null
            ? SelfEvolutionRunOptions.defaults()
            : options;
        String runId = Instant.now().toString().replace(':', '-');
        List<MisunderstandingSignal> signals = miner.mine(userId, memoryLimit);
        List<EvolutionEvalCase> cases = caseGenerator.generate(
            signals, userId, defaultBookId, defaultChapterIndex, resolved.caseCount());

        List<EvolutionCaseResult> baseline = judgeAll(
            cases, evaluator.evaluate(
                cases, runId + "-baseline", PromptOverride.none(),
                resolved.deterministicEvaluation()));
        SelfEvolutionReport.Aggregate baselineAggregate = aggregate(baseline);
        if (hasEvaluationFailure(baseline)) {
            return new SelfEvolutionReport(
                runId, userId, signals, cases, baseline, List.of(), PromptOverride.none(),
                baselineAggregate, aggregate(List.of()), false, false,
                "baseline 存在模型执行或评测器错误，实验无效；未生成或评测候选 Prompt。",
                List.of(), null, "BASELINE_EVALUATION_ERROR");
        }
        if (!shouldEvaluateCandidate(baselineAggregate)) {
            SelfEvolutionReport.Aggregate skippedCandidate = aggregate(List.of());
            return new SelfEvolutionReport(
                runId, userId, signals, cases, baseline, List.of(), PromptOverride.none(),
                baselineAggregate, skippedCandidate, true, false,
                "baseline 全部通过且没有硬失败，无需生成或评测候选 Prompt。",
                List.of(), null, "BASELINE_ALREADY_PASSES");
        }

        List<EvolutionIterationResult> iterations = new ArrayList<>();
        PromptOverride previousPrompt = PromptOverride.none();
        EvolutionIterationResult winner = null;
        boolean experimentValid = true;
        String stopReason = "MAX_ITERATIONS_REACHED";
        for (int iteration = 1; iteration <= resolved.maxCandidateIterations(); iteration++) {
            CandidateGenerationContext context =
                new CandidateGenerationContext(cases, baseline, iterations);
            PromptOverride prompt = promptGenerator.generate(context);
            if (prompt.isEmpty()) {
                stopReason = "EMPTY_CANDIDATE_PATCH";
                break;
            }
            if (!previousPrompt.isEmpty()
                && previousPrompt.finalAnswerPatch().equals(prompt.finalAnswerPatch())) {
                stopReason = "DUPLICATE_CANDIDATE_PATCH";
                break;
            }
            List<EvolutionCaseResult> candidate = judgeAll(
                cases, evaluator.evaluate(
                    cases,
                    runId + "-candidate-" + iteration,
                    prompt,
                    resolved.deterministicEvaluation()));
            SelfEvolutionReport.Aggregate candidateAggregate = aggregate(candidate);
            boolean valid = !hasEvaluationFailure(candidate);
            boolean safetyPassed = valid && candidateMeetsSafetyGate(candidate);
            boolean beatsBaseline = safetyPassed
                && isCandidateBetter(baselineAggregate, candidateAggregate);
            EvolutionIterationResult result = iterationResult(
                iteration, prompt, baseline, candidate, candidateAggregate,
                valid, safetyPassed, beatsBaseline);
            iterations.add(result);
            previousPrompt = prompt;
            if (!valid) {
                experimentValid = false;
                stopReason = "CANDIDATE_EVALUATION_ERROR";
                break;
            }
            if (beatsBaseline) {
                winner = result;
                stopReason = "STRICT_WINNER_FOUND";
                break;
            }
        }

        EvolutionIterationResult bestAttempt = winner != null
            ? winner
            : bestAttempt(iterations);
        List<EvolutionCaseResult> candidate = bestAttempt == null
            ? List.of()
            : bestAttempt.results();
        PromptOverride candidatePrompt = bestAttempt == null
            ? PromptOverride.none()
            : bestAttempt.prompt();
        SelfEvolutionReport.Aggregate candidateAggregate = bestAttempt == null
            ? aggregate(List.of())
            : bestAttempt.aggregate();
        boolean better = winner != null;
        String recommendation = recommendation(
            experimentValid, winner, bestAttempt, stopReason);
        return new SelfEvolutionReport(
            runId, userId, signals, cases, baseline, candidate, candidatePrompt,
            baselineAggregate, candidateAggregate, experimentValid, better, recommendation,
            iterations, winner == null ? null : winner.iteration(), stopReason);
    }

    private EvolutionIterationResult iterationResult(
        int iteration,
        PromptOverride prompt,
        List<EvolutionCaseResult> baseline,
        List<EvolutionCaseResult> candidate,
        SelfEvolutionReport.Aggregate aggregate,
        boolean valid,
        boolean safetyPassed,
        boolean beatsBaseline) {
        Map<String, EvolutionCaseResult> baselineById = baseline.stream()
            .collect(Collectors.toMap(EvolutionCaseResult::caseId, value -> value));
        List<String> fixed = transitionIds(
            candidate, baselineById,
            pair -> !pair.baseline().passed() && pair.candidate().passed());
        List<String> persistent = transitionIds(
            candidate, baselineById,
            pair -> !pair.baseline().passed() && !pair.candidate().passed());
        List<String> regressions = transitionIds(
            candidate, baselineById,
            pair -> pair.baseline().passed() && !pair.candidate().passed());
        return new EvolutionIterationResult(
            iteration, prompt, candidate, aggregate, valid, safetyPassed, beatsBaseline,
            fixed, persistent, regressions);
    }

    private List<String> transitionIds(
        List<EvolutionCaseResult> candidate,
        Map<String, EvolutionCaseResult> baselineById,
        Predicate<ResultPair> predicate) {
        return candidate.stream()
            .map(result -> new ResultPair(baselineById.get(result.caseId()), result))
            .filter(pair -> pair.baseline() != null && predicate.test(pair))
            .map(pair -> pair.candidate().caseId())
            .toList();
    }

    private EvolutionIterationResult bestAttempt(List<EvolutionIterationResult> iterations) {
        return iterations.stream().max(
            Comparator.comparing(EvolutionIterationResult::valid)
                .thenComparing(EvolutionIterationResult::safetyPassed)
                .thenComparingDouble(value -> value.aggregate().passRate())
                .thenComparingDouble(value -> value.aggregate().averageScore())
                .thenComparingInt(value -> -value.aggregate().hardFailures()))
            .orElse(null);
    }

    private String recommendation(boolean experimentValid,
                                  EvolutionIterationResult winner,
                                  EvolutionIterationResult bestAttempt,
                                  String stopReason) {
        if (!experimentValid) {
            return "candidate 存在模型执行或评测器错误，实验无效；不得推荐候选 Prompt。";
        }
        if (winner != null) {
            return "第 " + winner.iteration()
                + " 轮候选在同一批用例上严格优于 baseline 且通过证据安全门槛；"
                + "仅建议人工审阅，不自动应用。";
        }
        if (bestAttempt == null) {
            return "未生成可评测的 Candidate patch，保留 baseline。";
        }
        if (!bestAttempt.safetyPassed()) {
            return "多轮候选仍存在证据边界硬失败，不满足安全门槛，保留 baseline。";
        }
        return "多轮候选均未满足严格胜出条件，保留 baseline。停止原因：" + stopReason + "。";
    }

    private record ResultPair(EvolutionCaseResult baseline,
                              EvolutionCaseResult candidate) {
    }

    private List<EvolutionCaseResult> judgeAll(List<EvolutionEvalCase> cases,
                                               List<InProcessAgentEvaluator.AgentRun> runs) {
        List<EvolutionCaseResult> results = new ArrayList<>();
        for (int index = 0; index < cases.size(); index++) {
            results.add(judge.judge(cases.get(index), runs.get(index)));
        }
        return List.copyOf(results);
    }

    static SelfEvolutionReport.Aggregate aggregate(List<EvolutionCaseResult> results) {
        int evaluated = results == null ? 0 : results.size();
        int passed = results == null ? 0 : (int) results.stream().filter(EvolutionCaseResult::passed).count();
        int hardFailures = results == null ? 0
            : (int) results.stream().filter(EvolutionCaseResult::hardFailure).count();
        double average = evaluated == 0 ? 0.0
            : results.stream().mapToDouble(EvolutionCaseResult::score).average().orElse(0.0);
        double passRate = evaluated == 0 ? 0.0 : (double) passed / evaluated;
        return new SelfEvolutionReport.Aggregate(evaluated, passed, hardFailures, average, passRate);
    }

    static boolean isCandidateBetter(SelfEvolutionReport.Aggregate baseline,
                                     SelfEvolutionReport.Aggregate candidate) {
        return candidate.averageScore() > baseline.averageScore()
            && candidate.passRate() >= baseline.passRate()
            && candidate.hardFailures() <= baseline.hardFailures();
    }

    static boolean shouldEvaluateCandidate(SelfEvolutionReport.Aggregate baseline) {
        return baseline.evaluated() == 0
            || baseline.passed() < baseline.evaluated()
            || baseline.hardFailures() > 0;
    }

    static boolean candidateMeetsSafetyGate(List<EvolutionCaseResult> results) {
        return results != null && results.stream().noneMatch(result ->
            result.failureTypes().contains(FailureType.EVIDENCE_BOUNDARY)
                || result.failureTypes().contains(FailureType.EVALUATION_ERROR));
    }

    static boolean hasEvaluationFailure(List<EvolutionCaseResult> results) {
        return results != null && results.stream().anyMatch(result ->
            result.failureTypes().contains(FailureType.EMPTY_OR_MODEL_ERROR)
                || result.failureTypes().contains(FailureType.EVALUATION_ERROR));
    }
}
