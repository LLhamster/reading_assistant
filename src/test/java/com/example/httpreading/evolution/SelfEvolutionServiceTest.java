package com.example.httpreading.evolution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

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

    private EvolutionCaseResult result(List<FailureType> failures) {
        return new EvolutionCaseResult(
            "c1", "回答", "completed", null, failures.isEmpty() ? 1.0 : 0.49,
            failures.isEmpty(), !failures.isEmpty(), failures, List.of(), 1);
    }
}
