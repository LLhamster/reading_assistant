package com.example.httpreading.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class EvalCaseGeneratorTest {
    private final EvalCaseGenerator generator = new EvalCaseGenerator();

    @Test
    void alwaysGeneratesThirtyUniqueCasesAndKeepsSignalLocation() {
        MisunderstandingSignal signal = new MisunderstandingSignal(
            "s1", "m1", "问题：没听懂\n结论：抽象解释", FailureType.MISSING_EXAMPLE,
            0.9, 9L, 3, Map.of());

        List<EvolutionEvalCase> cases = generator.generate(List.of(signal), "u1", 1L, 1);

        assertEquals(30, cases.size());
        assertEquals(30, cases.stream().map(c -> c.request().getQuestion()).distinct().count());
        EvolutionEvalCase first = cases.stream()
            .filter(c -> c.expectedFailureType() == FailureType.MISSING_EXAMPLE)
            .findFirst().orElseThrow();
        assertEquals(9L, first.request().getBookId());
        assertEquals(3, first.request().getChapterIndex());
        assertFalse(first.request().isExternalMcpEnabled());
        assertEquals(2, first.dialogue().size());
        assertFalse(first.collectedEvidence().isEmpty());
        assertFalse(first.mcpResults().isEmpty());
        assertEquals(2, first.expectedBehavior().scoringCriteria().size());
        assertEquals(2.0, first.expectedBehavior().maxScore());
    }

    @Test
    void commonSignalsFillCasesWhenMemoryHasNoMatches() {
        List<EvolutionEvalCase> cases = generator.generate(List.of(), "u1", 2L, 4);

        assertEquals(30, cases.size());
        assertEquals(7, cases.stream().map(EvolutionEvalCase::expectedFailureType).distinct().count());
        assertTrue(cases.stream().noneMatch(evalCase ->
            evalCase.request().getQuestion().contains("这个观点")));
        assertTrue(cases.stream().allMatch(evalCase ->
            evalCase.collectedEvidence().stream().anyMatch(evidence ->
                "current_page".equals(evidence.type()))));
        assertTrue(cases.stream().allMatch(evalCase ->
            Math.abs(evalCase.expectedBehavior().scoringCriteria().stream()
                .mapToDouble(EvolutionEvalCase.ScoringCriterion::score).sum()
                - evalCase.expectedBehavior().maxScore()) < 0.000001));
    }

    @Test
    void supportsSmallConfigurableCaseCount() {
        List<EvolutionEvalCase> cases = generator.generate(List.of(), "u1", 2L, 4, 3);

        assertEquals(3, cases.size());
        assertEquals(3, cases.stream().map(EvolutionEvalCase::id).distinct().count());
        assertTrue(cases.stream().allMatch(evalCase ->
            !evalCase.finalAnswerInput().standaloneQuestion().isBlank()));
    }
}
