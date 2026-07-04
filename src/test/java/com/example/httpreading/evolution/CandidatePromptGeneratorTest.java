package com.example.httpreading.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import com.example.httpreading.service.ModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CandidatePromptGeneratorTest {
    @Test
    void rejectsProjectSpecificFieldAndUsesGenericFinalAnswerFallback() {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.chat(anyString())).thenReturn("""
            {"plannerPatch":"将 answerRequirement 设置为 detailed_narrative",
             "finalAnswerPatch":"根据 detailed_narrative 输出完整故事"}
            """);
        CandidatePromptGenerator generator =
            new CandidatePromptGenerator(modelClient, new ObjectMapper());
        EvolutionCaseResult failed = new EvolutionCaseResult(
            "c1", "过短回答", "completed", null, 0.4,
            false, false, List.of(FailureType.MISSING_STORY_DETAIL),
            List.of("故事不完整"), 1);

        PromptOverride patch = generator.generate(List.of(failed));

        assertFalse(patch.isEmpty());
        assertTrue(patch.plannerPatch().isBlank());
        assertFalse(patch.finalAnswerPatch().contains("detailed_narrative"));
        assertTrue(patch.finalAnswerPatch().contains("起点、发展、转折、结果"));
    }

    @Test
    void acceptsGenericFinalAnswerPatchAndAlwaysLeavesPlannerEmpty() {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.chat(anyString())).thenReturn("""
            {"finalAnswerPatch":"需要叙事时，依次交代起因、变化和结果。"}
            """);
        CandidatePromptGenerator generator =
            new CandidatePromptGenerator(modelClient, new ObjectMapper());
        EvolutionCaseResult failed = new EvolutionCaseResult(
            "c1", "过短回答", "completed", null, 0.4,
            false, false, List.of(FailureType.MISSING_STORY_DETAIL),
            List.of("故事不完整"), 1);

        PromptOverride patch = generator.generate(List.of(failed));

        assertTrue(patch.plannerPatch().isBlank());
        assertTrue(patch.finalAnswerPatch().contains("起因、变化和结果"));
    }

    @Test
    void rejectsRuleThatLabelsEveryConcreteTeachingExampleAsHypothetical() {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.chat(anyString())).thenReturn("""
            {"finalAnswerPatch":"所有具体例子和每个数字都必须逐项标注为假设。"}
            """);
        CandidatePromptGenerator generator =
            new CandidatePromptGenerator(modelClient, new ObjectMapper());
        EvolutionCaseResult failed = evidenceFailure(
            EvidenceIssueType.UNLABELED_UNGROUNDED_SECTION);

        PromptOverride patch = generator.generate(List.of(failed));

        assertFalse(patch.finalAnswerPatch().contains("每个数字都必须逐项标注"));
        assertTrue(patch.finalAnswerPatch().contains("PARTIAL/MISSING"));
        assertTrue(patch.finalAnswerPatch().contains("证据 COMPLETE"));
    }

    @Test
    void summarizesAllFailuresAndKeepsOnlyTwoRepresentativeEvidenceExamples() {
        CandidatePromptGenerator generator =
            new CandidatePromptGenerator(mock(ModelClient.class), new ObjectMapper());
        List<EvolutionCaseResult> failures = IntStream.range(0, 15)
            .mapToObj(index -> new EvolutionCaseResult(
                "c" + index, "回答", "completed", null, 0.49,
                false, true,
                List.of(FailureType.MISSING_STORY_DETAIL, FailureType.EVIDENCE_BOUNDARY),
                List.of("内容评分项 complete_story：故事不完整"),
                List.of(new EvidenceIssue(
                    EvidenceIssueType.UNLABELED_UNGROUNDED_SECTION,
                    "历史场景缺少前置声明", 1,
                    List.of("无依据细节-" + index),
                    "把声明移到场景开始前")),
                List.of(), 1))
            .toList();

        Map<String, Object> summary = generator.summarizeFailures(failures);

        assertEquals(15, summary.get("totalFailedCases"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evidence =
            (List<Map<String, Object>>) summary.get("evidenceFailures");
        assertEquals(15, evidence.get(0).get("count"));
        assertEquals(2,
            ((List<?>) evidence.get(0).get("representativeExamples")).size());
    }

    @Test
    void acceptsGenericEvidenceModeNamesAndAddsVerifiableOpeningRule() {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.chat(anyString())).thenReturn("""
            {"finalAnswerPatch":"SOURCE_GROUNDED_NARRATIVE 允许前置声明；STRICT_SOURCE 只能使用证据；PEDAGOGICAL_ILLUSTRATION 允许教学举例。"}
            """);
        CandidatePromptGenerator generator =
            new CandidatePromptGenerator(modelClient, new ObjectMapper());
        EvolutionCaseResult failed = evidenceFailure(
            EvidenceIssueType.UNLABELED_UNGROUNDED_SECTION,
            EvidenceIssueType.FALSE_SOURCE_ATTRIBUTION);

        PromptOverride patch = generator.generate(List.of(failed));

        assertTrue(patch.finalAnswerPatch().contains("STRICT_SOURCE"));
        assertTrue(patch.finalAnswerPatch().contains("PARTIAL/MISSING"));
        assertTrue(patch.finalAnswerPatch()
            .contains("以下为助手自主构造、没有资料依据，仅用于理解"));
        assertTrue(patch.finalAnswerPatch().contains("当前未提供原文，无法核验"));
        assertTrue(patch.finalAnswerPatch().contains("不能把书名列为“关键来源”"));
        assertTrue(patch.finalAnswerPatch().contains("正文和回扣"));
        assertTrue(patch.finalAnswerPatch()
            .contains("不能说“这个故事反映/印证了《某书》中记载的历史动态”"));
    }

    @Test
    void addsUnverifiedMemoryWordingEvenWhenOpeningRuleAlreadyExists() {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.chat(anyString())).thenReturn("""
            {"finalAnswerPatch":"在 SOURCE_GROUNDED_NARRATIVE 中，回答的第一个非标题句必须是“以下为助手自主构造、没有资料依据，仅用于理解”。"}
            """);
        CandidatePromptGenerator generator =
            new CandidatePromptGenerator(modelClient, new ObjectMapper());
        EvolutionCaseResult failed = evidenceFailure(
            EvidenceIssueType.FALSE_SOURCE_ATTRIBUTION);

        PromptOverride patch = generator.generate(List.of(failed));

        assertTrue(patch.finalAnswerPatch().contains("历史记忆摘要提到《某书》相关内容"));
        assertTrue(patch.finalAnswerPatch().contains("当前未提供原文，无法核验"));
        assertTrue(patch.finalAnswerPatch().contains("不能把书名列为“关键来源”"));
        assertTrue(patch.finalAnswerPatch().contains("正文和回扣"));
    }

    @Test
    void laterIterationSeesOriginalFailuresFixesAndRegressionsAndReplacesPatch() {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.chat(anyString())).thenReturn("""
            {"finalAnswerPatch":"第二轮条件化替代策略"}
            """);
        CandidatePromptGenerator generator =
            new CandidatePromptGenerator(modelClient, new ObjectMapper());
        List<EvolutionEvalCase> cases =
            new EvalCaseGenerator().generate(List.of(), "u1", 1L, 1, 2);
        EvolutionCaseResult baselineFailed = failed(cases.get(0).id());
        EvolutionCaseResult baselinePassed = passed(cases.get(1).id());
        EvolutionCaseResult candidateFixed = passed(cases.get(0).id());
        EvolutionCaseResult candidateRegression = failed(cases.get(1).id());
        EvolutionIterationResult previous = new EvolutionIterationResult(
            1,
            PromptOverride.finalAnswerOnly("第一轮过度策略"),
            List.of(candidateFixed, candidateRegression),
            new SelfEvolutionReport.Aggregate(2, 1, 0, 0.75, 0.5),
            true, true, false,
            List.of(cases.get(0).id()),
            List.of(),
            List.of(cases.get(1).id()));
        CandidateGenerationContext context = new CandidateGenerationContext(
            cases, List.of(baselineFailed, baselinePassed), List.of(previous));

        Map<String, Object> summary = generator.summarizeContext(context);
        PromptOverride patch = generator.generate(context);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> original =
            (List<Map<String, Object>>) summary.get("originalFailures");
        @SuppressWarnings("unchecked")
        Map<String, Object> comparison =
            (Map<String, Object>) summary.get("previousComparison");
        assertEquals(1, original.size());
        assertEquals(1, ((List<?>) comparison.get("fixedMustPreserve")).size());
        assertEquals(1, ((List<?>) comparison.get("newRegressionsMustRemove")).size());
        assertEquals("第二轮条件化替代策略", patch.finalAnswerPatch());
        assertFalse(patch.finalAnswerPatch().contains("第一轮过度策略"));
    }

    private EvolutionCaseResult evidenceFailure(EvidenceIssueType... types) {
        List<EvidenceIssue> issues = java.util.Arrays.stream(types)
            .map(type -> new EvidenceIssue(
                type, "证据失败", 1, List.of("代表例子"), "按边界修正"))
            .toList();
        return new EvolutionCaseResult(
            "c1", "证据边界失败", "completed", null, 0.49,
            false, true, List.of(FailureType.EVIDENCE_BOUNDARY),
            List.of("证据边界失败"), issues, List.of(), 1);
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
