package com.example.httpreading.evolution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class SelfEvolutionServiceTest {
    @Test
    void candidateMustImproveScoreWithoutPassRateOrHardFailureRegression() {
        SelfEvolutionReport.Aggregate baseline = new SelfEvolutionReport.Aggregate(30, 20, 1, 0.70, 2.0 / 3.0);

        assertTrue(SelfEvolutionService.isCandidateBetter(
            baseline, new SelfEvolutionReport.Aggregate(30, 22, 1, 0.78, 22.0 / 30.0)));
        assertFalse(SelfEvolutionService.isCandidateBetter(
            baseline, new SelfEvolutionReport.Aggregate(30, 19, 1, 0.78, 19.0 / 30.0)));
        assertFalse(SelfEvolutionService.isCandidateBetter(
            baseline, new SelfEvolutionReport.Aggregate(30, 22, 2, 0.78, 22.0 / 30.0)));
        assertFalse(SelfEvolutionService.isCandidateBetter(
            baseline, new SelfEvolutionReport.Aggregate(30, 22, 1, 0.70, 22.0 / 30.0)));
    }

    @Test
    void skipsCandidateWhenEveryBaselineCasePasses() {
        assertFalse(SelfEvolutionService.shouldEvaluateCandidate(
            new SelfEvolutionReport.Aggregate(5, 5, 0, 0.95, 1.0)));
        assertTrue(SelfEvolutionService.shouldEvaluateCandidate(
            new SelfEvolutionReport.Aggregate(5, 4, 0, 0.85, 0.8)));
        assertTrue(SelfEvolutionService.shouldEvaluateCandidate(
            new SelfEvolutionReport.Aggregate(5, 5, 1, 0.90, 1.0)));
    }

    @Test
    void candidateMustPassEvidenceSafetyGateAndEvaluationMustBeValid() {
        EvolutionCaseResult safe = result(List.of());
        EvolutionCaseResult evidenceFailure = result(List.of(FailureType.EVIDENCE_BOUNDARY));
        EvolutionCaseResult evaluationError = result(List.of(FailureType.EVALUATION_ERROR));
        EvolutionCaseResult modelError = result(List.of(FailureType.EMPTY_OR_MODEL_ERROR));

        assertTrue(SelfEvolutionService.candidateMeetsSafetyGate(List.of(safe)));
        assertFalse(SelfEvolutionService.candidateMeetsSafetyGate(List.of(evidenceFailure)));
        assertFalse(SelfEvolutionService.candidateMeetsSafetyGate(List.of(evaluationError)));
        assertTrue(SelfEvolutionService.hasEvaluationFailure(List.of(evaluationError)));
        assertTrue(SelfEvolutionService.hasEvaluationFailure(List.of(modelError)));
        assertFalse(SelfEvolutionService.hasEvaluationFailure(List.of(safe)));
    }

    @Test
    void runsAllBaselineJudgingBeforeOneAggregatedPatchAndCandidateBatch() {
        MisunderstandingMemoryMiner miner = mock(MisunderstandingMemoryMiner.class);
        EvalCaseGenerator caseGenerator = mock(EvalCaseGenerator.class);
        InProcessAgentEvaluator evaluator = mock(InProcessAgentEvaluator.class);
        RuleBasedJudge judge = mock(RuleBasedJudge.class);
        CandidatePromptGenerator promptGenerator = mock(CandidatePromptGenerator.class);
        SelfEvolutionService service = new SelfEvolutionService(
            miner, caseGenerator, evaluator, judge, promptGenerator);
        List<EvolutionEvalCase> cases =
            new EvalCaseGenerator().generate(List.of(), "u1", 1L, 1, 2);
        List<InProcessAgentEvaluator.AgentRun> baselineRuns = List.of(
            run(cases.get(0).id()), run(cases.get(1).id()));
        List<InProcessAgentEvaluator.AgentRun> candidateRuns = List.of(
            run(cases.get(0).id()), run(cases.get(1).id()));
        EvolutionCaseResult baselineOne = failed(cases.get(0).id());
        EvolutionCaseResult baselineTwo = failed(cases.get(1).id());
        EvolutionCaseResult candidateOne = passed(cases.get(0).id());
        EvolutionCaseResult candidateTwo = passed(cases.get(1).id());
        PromptOverride patch = PromptOverride.finalAnswerOnly("统一修正策略");

        when(miner.mine(anyString(), anyInt())).thenReturn(List.of());
        when(caseGenerator.generate(any(), anyString(), any(), any(), anyInt()))
            .thenReturn(cases);
        when(evaluator.evaluate(eq(cases), anyString(), any(PromptOverride.class)))
            .thenReturn(baselineRuns, candidateRuns);
        when(judge.judge(any(EvolutionEvalCase.class),
            any(InProcessAgentEvaluator.AgentRun.class)))
            .thenReturn(baselineOne, baselineTwo, candidateOne, candidateTwo);
        when(promptGenerator.generate(any())).thenReturn(patch);

        service.run("u1", 10, 1L, 1, 2);

        InOrder order = inOrder(evaluator, judge, promptGenerator);
        order.verify(evaluator).evaluate(eq(cases), anyString(), any(PromptOverride.class));
        order.verify(judge, times(2)).judge(
            any(EvolutionEvalCase.class), any(InProcessAgentEvaluator.AgentRun.class));
        order.verify(promptGenerator).generate(any());
        order.verify(evaluator).evaluate(eq(cases), anyString(), eq(patch));
        order.verify(judge, times(2)).judge(
            any(EvolutionEvalCase.class), any(InProcessAgentEvaluator.AgentRun.class));
        verify(promptGenerator, times(1)).generate(any());
    }

    private EvolutionCaseResult result(List<FailureType> failures) {
        return new EvolutionCaseResult(
            "c1", "回答", "completed", null, failures.isEmpty() ? 1.0 : 0.49,
            failures.isEmpty(), !failures.isEmpty(), failures, List.of(), 1);
    }

    private InProcessAgentEvaluator.AgentRun run(String caseId) {
        return new InProcessAgentEvaluator.AgentRun(
            caseId, "回答", "completed", null, 1, "");
    }

    private EvolutionCaseResult failed(String caseId) {
        return new EvolutionCaseResult(
            caseId, "失败回答", "completed", null, 0.5,
            false, false, List.of(FailureType.TOO_SIMPLE), List.of("内容不足"), 1);
    }

    private EvolutionCaseResult passed(String caseId) {
        return new EvolutionCaseResult(
            caseId, "通过回答", "completed", null, 1.0,
            true, false, List.of(), List.of(), 1);
    }
}
