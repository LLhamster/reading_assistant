package com.example.httpreading.evolution;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
            EvalCaseGenerator.DEFAULT_CASE_COUNT);
    }

    public SelfEvolutionReport run(String userId,
                                   int memoryLimit,
                                   Long defaultBookId,
                                   Integer defaultChapterIndex,
                                   int caseCount) {
        String runId = Instant.now().toString().replace(':', '-');
        List<MisunderstandingSignal> signals = miner.mine(userId, memoryLimit);
        List<EvolutionEvalCase> cases = caseGenerator.generate(
            signals, userId, defaultBookId, defaultChapterIndex, caseCount);

        List<EvolutionCaseResult> baseline = judgeAll(
            cases, evaluator.evaluate(cases, runId + "-baseline", PromptOverride.none()));
        SelfEvolutionReport.Aggregate baselineAggregate = aggregate(baseline);
        if (hasEvaluationFailure(baseline)) {
            return new SelfEvolutionReport(
                runId, userId, signals, cases, baseline, List.of(), PromptOverride.none(),
                baselineAggregate, aggregate(List.of()), false, false,
                "baseline 存在模型执行或评测器错误，实验无效；未生成或评测候选 Prompt。");
        }
        if (!shouldEvaluateCandidate(baselineAggregate)) {
            SelfEvolutionReport.Aggregate skippedCandidate = aggregate(List.of());
            return new SelfEvolutionReport(
                runId, userId, signals, cases, baseline, List.of(), PromptOverride.none(),
                baselineAggregate, skippedCandidate, true, false,
                "baseline 全部通过且没有硬失败，无需生成或评测候选 Prompt。");
        }

        PromptOverride candidatePrompt = promptGenerator.generate(baseline);
        if (candidatePrompt.isEmpty()) {
            SelfEvolutionReport.Aggregate skippedCandidate = aggregate(List.of());
            boolean onlyExecutionFailures = baseline.stream().allMatch(result ->
                result.failureTypes().contains(FailureType.EMPTY_OR_MODEL_ERROR));
            String recommendation = onlyExecutionFailures
                ? "baseline 全部为模型或执行错误，实验无效；未生成或评测候选 Prompt。"
                : "未生成有效的 FinalAnswer 候选 patch；未运行等价 candidate，保留 baseline。";
            return new SelfEvolutionReport(
                runId, userId, signals, cases, baseline, List.of(), PromptOverride.none(),
                baselineAggregate, skippedCandidate, !onlyExecutionFailures, false, recommendation);
        }
        List<EvolutionCaseResult> candidate = judgeAll(
            cases, evaluator.evaluate(cases, runId + "-candidate", candidatePrompt));

        SelfEvolutionReport.Aggregate candidateAggregate = aggregate(candidate);
        boolean experimentValid = !hasEvaluationFailure(candidate);
        boolean better = experimentValid
            && candidateMeetsSafetyGate(candidate)
            && isCandidateBetter(baselineAggregate, candidateAggregate);
        String recommendation;
        if (!experimentValid) {
            recommendation = "candidate 存在模型执行或评测器错误，实验无效；不得推荐候选 Prompt。";
        } else if (!candidateMeetsSafetyGate(candidate)) {
            recommendation = "candidate 仍存在证据边界硬失败，不满足安全门槛，保留 baseline。";
        } else {
            recommendation = better
                ? "候选 Prompt 在同一批用例上优于 baseline 且通过证据安全门槛；仅建议人工审阅，不自动应用。"
                : "候选 Prompt 未满足胜出条件，保留 baseline。";
        }
        return new SelfEvolutionReport(
            runId, userId, signals, cases, baseline, candidate, candidatePrompt,
            baselineAggregate, candidateAggregate, experimentValid, better, recommendation);
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
