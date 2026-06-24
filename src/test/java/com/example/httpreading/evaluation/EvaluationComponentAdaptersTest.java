package com.example.httpreading.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.example.httpreading.dto.AiChatResponse;
import com.example.httpreading.service.AiChatService;
import com.example.httpreading.service.ai.AnswerMode;
import com.example.httpreading.service.ai.AnswerRequirement;
import com.example.httpreading.service.ai.ChatPlan;
import com.example.httpreading.service.ai.EvidenceStrictness;
import com.example.httpreading.service.ai.PlannerService;
import com.example.httpreading.service.ai.PlannerTaskType;
import com.example.httpreading.service.ai.SubIntent;
import com.example.httpreading.service.ai.ToolExecutionMode;
import org.junit.jupiter.api.Test;

class EvaluationComponentAdaptersTest {
    @Test
    void plannerAdapterRunsRealPlannerInterface() throws Exception {
        PlannerService planner = mock(PlannerService.class);
        when(planner.plan(any())).thenReturn(plan());
        EvaluationReplayRunner.AgentResult result = EvaluationComponentAdapters.planner(planner).run(example());
        assertEquals("BOUNDED_REACT", result.route().plannerMode());
        assertEquals("self-local", result.route().plannerServer());
    }

    @Test
    void endToEndAdapterRunsAiChatServiceAndCapturesTrace() throws Exception {
        AiChatService service = mock(AiChatService.class);
        AiChatResponse response = new AiChatResponse("回答", List.of("e1"));
        response.setExternalMcpPlanRefs(List.of(
            "PLAN_MODE BOUNDED_REACT", "MCP_ROUTE self-local: selected", "PLAN_STEP 1 OK rag.search"));
        when(service.chat(any())).thenReturn(response);
        EvaluationReplayRunner.AgentResult result = EvaluationComponentAdapters.endToEnd(service).run(example());
        assertEquals("回答", result.answer());
        assertEquals(List.of("rag.search"), result.trace().tools());
        assertEquals(List.of("e1"), result.trace().evidenceIds());
    }

    private EvaluationCases.EvaluationExample example() {
        EvaluationCases.TaskInput input = new EvaluationCases.TaskInput("问题",
            new EvaluationCases.RoutingContext("u", "s", 1L, 1, "章", null, null, true, true),
            null, List.of(), List.of(), List.of());
        EvaluationCases.ExpectedResult expected = new EvaluationCases.ExpectedResult(
            "BOUNDED_REACT", "self-local", List.of("rag.search"));
        return new EvaluationCases.EvaluationExample("x", EvaluationCases.TOOL_ROUTING, input, expected, null,
            "MEDIUM", "BOOK_FACT", "golden", "dev", Map.of());
    }

    private ChatPlan plan() {
        return new ChatPlan("问题", "问题", "需要阅读证据", PlannerTaskType.READING_QA, SubIntent.NONE,
            AnswerRequirement.normal(), AnswerMode.TEXT_ONLY, EvidenceStrictness.STRICT, true,
            ToolExecutionMode.BOUNDED_REACT, List.of("mcp.server:self-local"), List.of(),
            "检索", 3, "证据足够", "按证据回答");
    }
}
