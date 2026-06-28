package com.example.httpreading.evolution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

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
        EvolutionCaseResult failed = new EvolutionCaseResult(
            "c1", "证据边界失败", "completed", null, 0.4,
            false, true, List.of(FailureType.EVIDENCE_BOUNDARY),
            List.of("历史场景缺少前置限定"), 1);

        PromptOverride patch = generator.generate(List.of(failed));

        assertFalse(patch.finalAnswerPatch().contains("每个数字都必须逐项标注"));
        assertTrue(patch.finalAnswerPatch().contains("教学例子无需声明真实性"));
    }
}
