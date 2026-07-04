package com.example.httpreading.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.example.httpreading.service.ModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        assertEquals(10, cases.stream()
            .map(evalCase -> evalCase.boundarySpec().boundary())
            .distinct().count());
    }

    @Test
    void commonSignalsFillCasesWhenMemoryHasNoMatches() {
        List<EvolutionEvalCase> cases = generator.generate(List.of(), "u1", 2L, 4);

        assertEquals(30, cases.size());
        assertEquals(7, cases.stream().map(EvolutionEvalCase::expectedFailureType).distinct().count());
        assertEquals(10, cases.stream()
            .map(evalCase -> evalCase.boundarySpec().boundary())
            .distinct().count());
        for (ReadingBoundary boundary : ReadingBoundary.values()) {
            assertEquals(3, cases.stream()
                .filter(evalCase -> evalCase.boundarySpec().boundary() == boundary)
                .count());
        }
        assertTrue(cases.stream().noneMatch(evalCase ->
            evalCase.request().getQuestion().matches(".*（变体\\d+）.*")));
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

    @Test
    void tenCasesCoverEveryBoundaryEvenWithARealSignal() {
        MisunderstandingSignal signal = new MisunderstandingSignal(
            "s1", "m1", "问题：讲完整故事\n结论：态度发生变化",
            FailureType.MISSING_STORY_DETAIL, 0.9, 9L, 3, Map.of());

        List<EvolutionEvalCase> cases =
            generator.generate(List.of(signal), "u1", 1L, 1, 10);

        assertEquals(10, cases.size());
        assertEquals(10, cases.stream()
            .map(evalCase -> evalCase.boundarySpec().boundary())
            .distinct().count());
    }

    @Test
    void mapsEvidenceUseModeFromTaskSemanticsInsteadOfExampleDetails() {
        List<EvolutionEvalCase> common = generator.generate(List.of(), "u1", 2L, 4, 10);

        assertEquals(EvidenceUseMode.STRICT_SOURCE,
            modeFor(common, ReadingBoundary.DIRECT_TEXT_FACT));
        assertEquals(EvidenceUseMode.STRICT_SOURCE,
            modeFor(common, ReadingBoundary.INSUFFICIENT_EVIDENCE));
        assertEquals(EvidenceUseMode.SOURCE_GROUNDED_NARRATIVE,
            modeFor(common, ReadingBoundary.SOURCE_NARRATIVE));
        assertEquals(EvidenceUseMode.PEDAGOGICAL_ILLUSTRATION,
            modeFor(common, ReadingBoundary.PEDAGOGICAL_EXAMPLE));

        MisunderstandingSignal strictSignal = new MisunderstandingSignal(
            "strict", "m1", "问题：只根据原文回答，不要补充任何例子。\n结论：原文说明资源有限。",
            FailureType.NOT_DIRECT, 0.9, 2L, 4, Map.of());
        EvolutionEvalCase strictCase =
            generator.generate(List.of(strictSignal), "u1", 2L, 4, 1).get(0);
        assertEquals(EvidenceUseMode.STRICT_SOURCE,
            strictCase.expectedBehavior().evidencePolicy().evidenceUseMode());
    }

    @Test
    void generatedModeFromLlmTakesPrecedenceOverKeywordFallback() {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.chat(anyString())).thenReturn("""
            {"cases":[{
              "signalId":"semantic-1",
              "failureType":"NOT_DIRECT",
              "evidenceUseMode":"PEDAGOGICAL_ILLUSTRATION",
              "reason":"任务实际要求用理论解释帮助理解，不承担原文事实声明"
            }]}
            """);
        EvalCaseGenerator llmGenerator =
            new EvalCaseGenerator(modelClient, new ObjectMapper());
        MisunderstandingSignal signal = new MisunderstandingSignal(
            "semantic-1", "m1",
            "问题：只根据原文这个说法来帮助我理解机会成本。\n结论：选择意味着放弃替代收益。",
            FailureType.NOT_DIRECT, 0.9, 2L, 4, Map.of());

        EvolutionEvalCase evalCase =
            llmGenerator.generate(List.of(signal), "u1", 2L, 4, 1).get(0);

        assertEquals(EvidenceUseMode.PEDAGOGICAL_ILLUSTRATION,
            evalCase.expectedBehavior().evidencePolicy().evidenceUseMode());
    }

    @Test
    void invalidGeneratedModeFallsBackToKeywordRules() {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.chat(anyString())).thenReturn("""
            {"cases":[{
              "signalId":"strict-1",
              "failureType":"NOT_DIRECT",
              "evidenceUseMode":"UNKNOWN"
            }]}
            """);
        EvalCaseGenerator llmGenerator =
            new EvalCaseGenerator(modelClient, new ObjectMapper());
        MisunderstandingSignal signal = new MisunderstandingSignal(
            "strict-1", "m1",
            "问题：只能依据原文回答，不得补充。\n结论：原文说明资源有限。",
            FailureType.NOT_DIRECT, 0.9, 2L, 4, Map.of());

        EvolutionEvalCase evalCase =
            llmGenerator.generate(List.of(signal), "u1", 2L, 4, 1).get(0);

        assertEquals(EvidenceUseMode.STRICT_SOURCE,
            evalCase.expectedBehavior().evidencePolicy().evidenceUseMode());
    }

    @Test
    void commonTemplatesDeclareModeWithoutCallingClassifier() {
        ModelClient modelClient = mock(ModelClient.class);
        EvalCaseGenerator llmGenerator =
            new EvalCaseGenerator(modelClient, new ObjectMapper());

        List<EvolutionEvalCase> cases =
            llmGenerator.generate(List.of(), "u1", 2L, 4, 7);

        verifyNoInteractions(modelClient);
        assertTrue(cases.stream().allMatch(evalCase ->
            evalCase.expectedBehavior().evidencePolicy().evidenceUseMode() != null));
    }

    private EvidenceUseMode modeFor(List<EvolutionEvalCase> cases, ReadingBoundary boundary) {
        return cases.stream()
            .filter(evalCase -> evalCase.boundarySpec().boundary() == boundary)
            .findFirst()
            .orElseThrow()
            .expectedBehavior()
            .evidencePolicy()
            .evidenceUseMode();
    }
}
