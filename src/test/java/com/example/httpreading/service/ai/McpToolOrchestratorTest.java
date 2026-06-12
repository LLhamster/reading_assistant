package com.example.httpreading.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.mcp.ExternalMcpAgentResult;
import com.example.httpreading.mcp.ExternalMcpAgentService;
import com.example.httpreading.mcp.ExternalMcpCallResult;
import com.example.httpreading.mcp.ExternalMcpClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class McpToolOrchestratorTest {
    @Mock
    private LocalMcpToolAdapter localMcpToolAdapter;
    @Mock
    private ExternalMcpClientService externalMcpClientService;
    @Mock
    private ExternalMcpAgentService externalMcpAgentService;

    private McpToolOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new McpToolOrchestrator(localMcpToolAdapter, externalMcpClientService, externalMcpAgentService);
    }

    @Test
    void singleToolCallsOnlySpecifiedLocalTool() {
        when(localMcpToolAdapter.call(eq("rag.search"), any()))
            .thenReturn(ExternalMcpCallResult.success("local", "rag.search", "{}"));

        ToolExecutionResult result = orchestrator.execute(request(), plan(
            ToolExecutionMode.SINGLE_TOOL,
            List.of("rag.search"),
            List.of(new ToolStep("rag.search", Map.of("query", "q"), "search"))));

        assertEquals(1, result.results().size());
        verify(localMcpToolAdapter).call(eq("rag.search"), any());
        verify(externalMcpClientService, never()).callTool(any());
    }

    @Test
    void multiToolRunsInStrictOrder() {
        when(localMcpToolAdapter.call(eq("context.get_recent_dialogue"), any()))
            .thenReturn(ExternalMcpCallResult.success("local", "context.get_recent_dialogue", "{}"));
        when(localMcpToolAdapter.call(eq("rag.search"), any()))
            .thenReturn(ExternalMcpCallResult.success("local", "rag.search", "{}"));

        ToolExecutionResult result = orchestrator.execute(request(), plan(
            ToolExecutionMode.MULTI_TOOL,
            List.of("context.get_recent_dialogue", "rag.search"),
            List.of(
                new ToolStep("context.get_recent_dialogue", Map.of(), "history"),
                new ToolStep("rag.search", Map.of("query", "q"), "rag"))));

        assertEquals(2, result.results().size());
        assertTrue(result.planRefs().stream().anyMatch(ref -> ref.contains("PLAN_STEP 1 OK context.get_recent_dialogue")));
        assertTrue(result.planRefs().stream().anyMatch(ref -> ref.contains("PLAN_STEP 2 OK rag.search")));
    }

    @Test
    void boundedReactDelegatesToAgentWithPlanContext() {
        AiChatRequest request = request();
        request.setEnableExternalMcp(true);
        when(externalMcpAgentService.execute(eq(request), any()))
            .thenReturn(new ExternalMcpAgentResult(
                List.of(ExternalMcpCallResult.success("github", "search_code", "content")),
                List.of("AUTO_COMPLETE"),
                ""));

        ToolExecutionResult result = orchestrator.execute(request, plan(
            ToolExecutionMode.BOUNDED_REACT,
            List.of("github.search_code"),
            List.of()));

        assertEquals(1, result.results().size());
        assertTrue(result.planRefs().contains("AUTO_COMPLETE"));
        verify(localMcpToolAdapter, never()).call(any(), any());
    }

    @Test
    void boundedReactShouldPassSelectedMcpServerToExternalAgent() {
        AiChatRequest request = request();
        request.setEnableExternalMcp(true);
        when(externalMcpAgentService.execute(eq(request), any(), eq("github")))
            .thenReturn(new ExternalMcpAgentResult(
                List.of(ExternalMcpCallResult.success("github", "search_repositories", "content")),
                List.of("MCP_ROUTE github: selected by Planner", "AUTO_COMPLETE"),
                ""));

        ToolExecutionResult result = orchestrator.execute(request, plan(
            ToolExecutionMode.BOUNDED_REACT,
            List.of("mcp.server:github"),
            List.of()));

        assertEquals(1, result.results().size());
        assertTrue(result.planRefs().stream().anyMatch(ref -> ref.contains("PLAN_MODE BOUNDED_REACT")));
        assertTrue(result.planRefs().stream().anyMatch(ref -> ref.contains("MCP_ROUTE github")));
        verify(externalMcpAgentService).execute(eq(request), any(), eq("github"));
        verify(externalMcpAgentService, never()).execute(eq(request), any());
    }


    @Test
    void deterministicModeBlocksToolsOutsideAllowedList() {
        ToolExecutionResult result = orchestrator.execute(request(), plan(
            ToolExecutionMode.SINGLE_TOOL,
            List.of("rag.search"),
            List.of(new ToolStep("memory.search", Map.of("query", "q"), "memory"))));

        assertEquals(1, result.results().size());
        assertTrue(result.results().get(0).getError().contains("allowedTools"));
        verify(localMcpToolAdapter, never()).call(any(), any());
    }

    private AiChatRequest request() {
        AiChatRequest request = new AiChatRequest();
        request.setBookId(1L);
        request.setChapterIndex(1);
        request.setQuestion("q");
        return request;
    }

    private ChatPlan plan(ToolExecutionMode mode, List<String> allowedTools, List<ToolStep> steps) {
        return new ChatPlan("q", "q", "test", PlannerTaskType.READING_QA, true,
            mode, allowedTools, steps, "goal", 5, "stop", "guidance");
    }
}
