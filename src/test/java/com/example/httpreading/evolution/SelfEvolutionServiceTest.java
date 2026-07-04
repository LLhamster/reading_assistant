package com.example.httpreading.evolution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
import org.mockito.ArgumentCaptor;

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
        when(evaluator.evaluate(
            eq(cases), anyString(), any(PromptOverride.class), anyBoolean()))
            .thenReturn(baselineRuns, candidateRuns);
        when(judge.judge(any(EvolutionEvalCase.class),
            any(InProcessAgentEvaluator.AgentRun.class)))
            .thenReturn(baselineOne, baselineTwo, candidateOne, candidateTwo);
        when(promptGenerator.generate(any(CandidateGenerationContext.class))).thenReturn(patch);

        service.run("u1", 10, 1L, 1, 2);

        InOrder order = inOrder(evaluator, judge, promptGenerator);
        order.verify(evaluator).evaluate(
            eq(cases), anyString(), any(PromptOverride.class), eq(true));
        order.verify(judge, times(2)).judge(
            any(EvolutionEvalCase.class), any(InProcessAgentEvaluator.AgentRun.class));
        order.verify(promptGenerator).generate(any(CandidateGenerationContext.class));
        order.verify(evaluator).evaluate(eq(cases), anyString(), eq(patch), eq(true));
        order.verify(judge, times(2)).judge(
            any(EvolutionEvalCase.class), any(InProcessAgentEvaluator.AgentRun.class));
        verify(promptGenerator, times(1)).generate(any(CandidateGenerationContext.class));
    }

    @Test
    void rewritesCandidateFromOriginalFailuresFixedCasesAndRegressions() {
        MisunderstandingMemoryMiner miner = mock(MisunderstandingMemoryMiner.class);
        EvalCaseGenerator caseGenerator = mock(EvalCaseGenerator.class);
        InProcessAgentEvaluator evaluator = mock(InProcessAgentEvaluator.class);
        RuleBasedJudge judge = mock(RuleBasedJudge.class);
        CandidatePromptGenerator promptGenerator = mock(CandidatePromptGenerator.class);
        SelfEvolutionService service = new SelfEvolutionService(
            miner, caseGenerator, evaluator, judge, promptGenerator);
        List<EvolutionEvalCase> cases =
            new EvalCaseGenerator().generate(List.of(), "u1", 1L, 1, 2);
        List<InProcessAgentEvaluator.AgentRun> runs = List.of(
            run(cases.get(0).id()), run(cases.get(1).id()));
        EvolutionCaseResult baselineFailed = failed(cases.get(0).id());
        EvolutionCaseResult baselinePassed = passed(cases.get(1).id());
        EvolutionCaseResult firstFixed = passed(cases.get(0).id());
        EvolutionCaseResult firstRegression = evidenceFailed(cases.get(1).id());
        EvolutionCaseResult secondPassedOne = passed(cases.get(0).id());
        EvolutionCaseResult secondPassedTwo = passed(cases.get(1).id());
        PromptOverride firstPatch = PromptOverride.finalAnswerOnly("第一轮完整策略");
        PromptOverride secondPatch = PromptOverride.finalAnswerOnly("第二轮替代策略");

        when(miner.mine(anyString(), anyInt())).thenReturn(List.of());
        when(caseGenerator.generate(any(), anyString(), any(), any(), anyInt()))
            .thenReturn(cases);
        when(evaluator.evaluate(
            eq(cases), anyString(), any(PromptOverride.class), eq(true)))
            .thenReturn(runs, runs, runs);
        when(judge.judge(any(EvolutionEvalCase.class),
            any(InProcessAgentEvaluator.AgentRun.class)))
            .thenReturn(
                baselineFailed, baselinePassed,
                firstFixed, firstRegression,
                secondPassedOne, secondPassedTwo);
        when(promptGenerator.generate(any(CandidateGenerationContext.class)))
            .thenReturn(firstPatch, secondPatch);

        SelfEvolutionReport report = service.run(
            "u1", 10, 1L, 1,
            new SelfEvolutionRunOptions(2, 3, true));

        assertTrue(report.candidateBetter());
        assertTrue(report.selectedIteration() == 2);
        assertTrue(report.iterations().size() == 2);
        assertTrue(report.iterations().get(0).fixedCaseIds().contains(cases.get(0).id()));
        assertTrue(report.iterations().get(0).regressionCaseIds().contains(cases.get(1).id()));
        assertTrue(report.candidatePrompt().finalAnswerPatch().equals("第二轮替代策略"));

        ArgumentCaptor<CandidateGenerationContext> contexts =
            ArgumentCaptor.forClass(CandidateGenerationContext.class);
        verify(promptGenerator, times(2)).generate(contexts.capture());
        assertTrue(contexts.getAllValues().get(0).priorIterations().isEmpty());
        assertTrue(contexts.getAllValues().get(1).priorIterations().size() == 1);
        assertTrue(contexts.getAllValues().get(1).previousIteration()
            .regressionCaseIds().contains(cases.get(1).id()));
    }

    @Test
    void stopsBeforeReevaluatingAnIdenticalReplacementPatch() {
        ServiceFixture fixture = fixtureWithOneFailedCase();
        PromptOverride same = PromptOverride.finalAnswerOnly("相同替代策略");
        when(fixture.promptGenerator().generate(any(CandidateGenerationContext.class)))
            .thenReturn(same, same);

        SelfEvolutionReport report = fixture.service().run(
            "u1", 10, 1L, 1,
            new SelfEvolutionRunOptions(1, 3, true));

        assertTrue(report.iterations().size() == 1);
        assertTrue(report.stopReason().equals("DUPLICATE_CANDIDATE_PATCH"));
        verify(fixture.evaluator(), times(2)).evaluate(
            eq(fixture.cases()), anyString(), any(PromptOverride.class), eq(true));
    }

    @Test
    void stopsAtConfiguredThreeCandidateIterationsWithoutWinner() {
        ServiceFixture fixture = fixtureWithOneFailedCase();
        when(fixture.promptGenerator().generate(any(CandidateGenerationContext.class)))
            .thenReturn(
                PromptOverride.finalAnswerOnly("第一轮"),
                PromptOverride.finalAnswerOnly("第二轮"),
                PromptOverride.finalAnswerOnly("第三轮"));

        SelfEvolutionReport report = fixture.service().run(
            "u1", 10, 1L, 1,
            new SelfEvolutionRunOptions(1, 3, true));

        assertTrue(report.iterations().size() == 3);
        assertTrue(report.stopReason().equals("MAX_ITERATIONS_REACHED"));
        assertFalse(report.candidateBetter());
        verify(fixture.evaluator(), times(4)).evaluate(
            eq(fixture.cases()), anyString(), any(PromptOverride.class), eq(true));
    }

    private ServiceFixture fixtureWithOneFailedCase() {
        MisunderstandingMemoryMiner miner = mock(MisunderstandingMemoryMiner.class);
        EvalCaseGenerator caseGenerator = mock(EvalCaseGenerator.class);
        InProcessAgentEvaluator evaluator = mock(InProcessAgentEvaluator.class);
        RuleBasedJudge judge = mock(RuleBasedJudge.class);
        CandidatePromptGenerator promptGenerator = mock(CandidatePromptGenerator.class);
        SelfEvolutionService service = new SelfEvolutionService(
            miner, caseGenerator, evaluator, judge, promptGenerator);
        List<EvolutionEvalCase> cases =
            new EvalCaseGenerator().generate(List.of(), "u1", 1L, 1, 1);
        List<InProcessAgentEvaluator.AgentRun> runs = List.of(run(cases.get(0).id()));
        when(miner.mine(anyString(), anyInt())).thenReturn(List.of());
        when(caseGenerator.generate(any(), anyString(), any(), any(), anyInt()))
            .thenReturn(cases);
        when(evaluator.evaluate(
            eq(cases), anyString(), any(PromptOverride.class), eq(true)))
            .thenReturn(runs);
        when(judge.judge(any(EvolutionEvalCase.class),
            any(InProcessAgentEvaluator.AgentRun.class)))
            .thenReturn(failed(cases.get(0).id()));
        return new ServiceFixture(service, evaluator, promptGenerator, cases);
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

    private EvolutionCaseResult evidenceFailed(String caseId) {
        return new EvolutionCaseResult(
            caseId, "证据失败回答", "completed", null, 0.49,
            false, true, List.of(FailureType.EVIDENCE_BOUNDARY),
            List.of("证据边界失败"), 1);
    }

    private record ServiceFixture(SelfEvolutionService service,
                                  InProcessAgentEvaluator evaluator,
                                  CandidatePromptGenerator promptGenerator,
                                  List<EvolutionEvalCase> cases) {
    }
}
