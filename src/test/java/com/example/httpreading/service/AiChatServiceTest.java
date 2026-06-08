package com.example.httpreading.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.example.httpreading.context.manager.DefaultContextManager;
import com.example.httpreading.dto.AiChatInteraction;
import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.dto.AiChatResponse;
import com.example.httpreading.mcp.ExternalMcpAgentService;
import com.example.httpreading.mcp.ExternalMcpCallResult;
import com.example.httpreading.service.ai.ChatPlan;
import com.example.httpreading.service.ai.CollectedEvidence;
import com.example.httpreading.service.ai.EvidenceAggregator;
import com.example.httpreading.service.ai.EvidenceItem;
import com.example.httpreading.service.ai.FinalAnswerService;
import com.example.httpreading.service.ai.McpToolOrchestrator;
import com.example.httpreading.service.ai.MemoryWriter;
import com.example.httpreading.service.ai.PlannerService;
import com.example.httpreading.service.ai.PlannerTaskType;
import com.example.httpreading.service.ai.ToolExecutionMode;
import com.example.httpreading.service.ai.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiChatServiceTest {
    @Mock
    private RagService ragService;
    @Mock
    private ExternalMcpAgentService externalMcpAgentService;
    @Mock
    private PlannerService plannerService;
    @Mock
    private McpToolOrchestrator mcpToolOrchestrator;
    @Mock
    private EvidenceAggregator evidenceAggregator;
    @Mock
    private FinalAnswerService finalAnswerService;
    @Mock
    private MemoryWriter memoryWriter;

    private AiChatService aiChatService;

    @BeforeEach
    void setUp() {
        aiChatService = new AiChatService(
            ragService,
            new DefaultContextManager(),
            externalMcpAgentService,
            plannerService,
            mcpToolOrchestrator,
            evidenceAggregator,
            finalAnswerService,
            memoryWriter);
    }

    @Test
    void chatRunsPlanExecuteAggregateAnswerAndMemoryWriter() {
        AiChatRequest request = request();
        ChatPlan plan = plan(ToolExecutionMode.MULTI_TOOL);
        ToolExecutionResult execution = ToolExecutionResult.completed(
            List.of(ExternalMcpCallResult.success("local", "rag.search", "{\"ok\":true,\"data\":[]}")),
            List.of("PLAN_MODE MULTI_TOOL"));
        CollectedEvidence evidence = evidence();
        when(plannerService.plan(request)).thenReturn(plan);
        when(mcpToolOrchestrator.execute(request, plan)).thenReturn(execution);
        when(evidenceAggregator.aggregate(request, plan, execution)).thenReturn(evidence);
        when(finalAnswerService.answer(request, plan, evidence)).thenReturn("最终回答");

        AiChatResponse response = aiChatService.chat(request);

        assertEquals("最终回答", response.getAnswer());
        assertEquals(List.of("当前阅读页面划词：章节"), response.getSources());
        assertEquals(List.of("[working] 用户偏好"), response.getMemoryRefs());
        assertEquals(List.of("OK local/rag.search"), response.getExternalMcpRefs());
        assertEquals(List.of("PLAN_MODE MULTI_TOOL"), response.getExternalMcpPlanRefs());
        assertEquals("completed", response.getStatus());
        verify(memoryWriter).write(request, plan, evidence, "最终回答");
        verify(externalMcpAgentService).cancelPending(request);
    }

    @Test
    void needsConfirmationReturnsInteractionWithoutFinalAnswerOrMemoryWrite() {
        AiChatRequest request = request();
        ChatPlan plan = plan(ToolExecutionMode.BOUNDED_REACT);
        AiChatInteraction interaction = new AiChatInteraction();
        interaction.setConfirmationId("confirm-1");
        interaction.setQuestion("请选择仓库");
        ToolExecutionResult execution = new ToolExecutionResult(
            List.of(ExternalMcpCallResult.success("github", "search_repositories", "[]")),
            List.of("AUTO_NEEDS_CONFIRMATION"),
            "请选择仓库",
            "needs_confirmation",
            interaction);
        when(plannerService.plan(request)).thenReturn(plan);
        when(mcpToolOrchestrator.execute(request, plan)).thenReturn(execution);

        AiChatResponse response = aiChatService.chat(request);

        assertEquals("needs_confirmation", response.getStatus());
        assertEquals("confirm-1", response.getInteraction().getConfirmationId());
        assertEquals("请选择仓库", response.getAnswer());
        verify(finalAnswerService, never()).answer(any(), any(), any());
        verify(memoryWriter, never()).write(any(), any(), any(), any());
    }

    @Test
    void confirmationRequestResumesPendingPlan() {
        AiChatRequest request = request();
        request.setConfirmationId("confirm-1");
        request.setSelectedOptionId("repo-1");
        ChatPlan plan = plan(ToolExecutionMode.BOUNDED_REACT);
        ToolExecutionResult execution = ToolExecutionResult.completed(List.of(), List.of("AUTO_COMPLETE"));
        CollectedEvidence evidence = evidence();
        when(plannerService.plan(request)).thenReturn(plan);
        when(mcpToolOrchestrator.resume(request, plan)).thenReturn(execution);
        when(evidenceAggregator.aggregate(request, plan, execution)).thenReturn(evidence);
        when(finalAnswerService.answer(request, plan, evidence)).thenReturn("恢复后的回答");

        AiChatResponse response = aiChatService.chat(request);

        assertEquals("恢复后的回答", response.getAnswer());
        verify(mcpToolOrchestrator).resume(request, plan);
        verify(mcpToolOrchestrator, never()).execute(any(), any());
    }

    private AiChatRequest request() {
        AiChatRequest request = new AiChatRequest();
        request.setBookId(1L);
        request.setChapterIndex(1);
        request.setQuestion("问题");
        request.setEnableExternalMcp(true);
        return request;
    }

    private ChatPlan plan(ToolExecutionMode mode) {
        return new ChatPlan(
            "问题",
            "问题",
            "测试规划",
            PlannerTaskType.READING_QA,
            true,
            mode,
            List.of("rag.search"),
            List.of(),
            "回答",
            3,
            "完成",
            "依据证据回答");
    }

    private CollectedEvidence evidence() {
        return new CollectedEvidence(
            List.of(new EvidenceItem("e1", "current_page", "当前阅读页面划词：章节", "内容", 10, 1.0d, java.util.Map.of())),
            List.of("当前阅读页面划词：章节"),
            List.of("[working] 用户偏好"),
            List.of("OK local/rag.search"),
            List.of("PLAN_MODE MULTI_TOOL"),
            "证据");
    }
}
