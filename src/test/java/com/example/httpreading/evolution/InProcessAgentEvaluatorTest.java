package com.example.httpreading.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.service.ai.ChatPlan;
import com.example.httpreading.service.ai.CollectedEvidence;
import com.example.httpreading.service.ai.FinalAnswerService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InProcessAgentEvaluatorTest {
    @Test
    void baselineAndCandidateReuseFixedFinalAnswerInputAndDifferOnlyByPatch() {
        FinalAnswerService finalAnswerService = mock(FinalAnswerService.class);
        when(finalAnswerService.answer(
            any(AiChatRequest.class), any(ChatPlan.class),
            any(CollectedEvidence.class), any(PromptOverride.class)))
            .thenReturn("最终回答");
        InProcessAgentEvaluator evaluator = new InProcessAgentEvaluator(finalAnswerService);
        EvolutionEvalCase evalCase = new EvalCaseGenerator()
            .generate(List.of(), "u1", 1L, 1, 1).get(0);

        evaluator.evaluate(List.of(evalCase), "baseline", PromptOverride.none());
        evaluator.evaluate(List.of(evalCase), "candidate",
            new PromptOverride("不应进入 Planner", "先给具体场景"));

        ArgumentCaptor<AiChatRequest> requests = ArgumentCaptor.forClass(AiChatRequest.class);
        ArgumentCaptor<ChatPlan> plans = ArgumentCaptor.forClass(ChatPlan.class);
        ArgumentCaptor<CollectedEvidence> evidence =
            ArgumentCaptor.forClass(CollectedEvidence.class);
        ArgumentCaptor<PromptOverride> overrides =
            ArgumentCaptor.forClass(PromptOverride.class);
        verify(finalAnswerService, times(2)).answer(
            requests.capture(), plans.capture(), evidence.capture(), overrides.capture());

        assertEquals(requests.getAllValues().get(0).resolvedSessionId(),
            requests.getAllValues().get(1).resolvedSessionId());
        assertEquals(plans.getAllValues().get(0), plans.getAllValues().get(1));
        assertTrue(plans.getAllValues().get(0).answerGuidance()
            .contains("evidenceUseMode=PEDAGOGICAL_ILLUSTRATION"));
        assertEquals(evidence.getAllValues().get(0), evidence.getAllValues().get(1));
        assertTrue(overrides.getAllValues().get(0).isEmpty());
        assertTrue(overrides.getAllValues().get(1).plannerPatch().isBlank());
        assertEquals("先给具体场景", overrides.getAllValues().get(1).finalAnswerPatch());
    }

    @Test
    void sourceGroundedNarrativeReceivesExactFirstNonTitleSentenceConstraint() {
        FinalAnswerService finalAnswerService = mock(FinalAnswerService.class);
        when(finalAnswerService.answer(
            any(AiChatRequest.class), any(ChatPlan.class),
            any(CollectedEvidence.class), any(PromptOverride.class)))
            .thenReturn("最终回答");
        InProcessAgentEvaluator evaluator = new InProcessAgentEvaluator(finalAnswerService);
        EvolutionEvalCase evalCase = new EvalCaseGenerator()
            .generate(List.of(), "u1", 1L, 1, 6).stream()
            .filter(value -> value.expectedBehavior().evidencePolicy().evidenceUseMode()
                == EvidenceUseMode.SOURCE_GROUNDED_NARRATIVE)
            .findFirst().orElseThrow();

        evaluator.evaluate(List.of(evalCase), "candidate",
            PromptOverride.finalAnswerOnly("候选策略"));

        ArgumentCaptor<ChatPlan> plans = ArgumentCaptor.forClass(ChatPlan.class);
        verify(finalAnswerService).answer(
            any(AiChatRequest.class), plans.capture(),
            any(CollectedEvidence.class), any(PromptOverride.class));
        assertTrue(plans.getValue().answerGuidance().contains("第一个非标题句必须是"));
        assertTrue(plans.getValue().answerGuidance()
            .contains("以下为助手自主构造、没有资料依据，仅用于理解"));
    }
}
