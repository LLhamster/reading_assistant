package com.example.httpreading.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.dto.ExternalMcpCall;
import com.example.httpreading.service.ModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExternalMcpToolPlannerServiceTest {
    @Mock
    private ModelClient modelClient;

    private ExternalMcpToolPlannerService plannerService;

    @BeforeEach
    void setUp() {
        plannerService = new ExternalMcpToolPlannerService(modelClient, new ObjectMapper());
    }

    @Test
    void parsesSingleRoundToolDecision() {
        when(modelClient.chat(anyString())).thenReturn("""
            {"status":"call_tool","assessment":"仓库名可能不完整","reasoningSummary":"先搜索仓库",\
             "call":{"serverName":"github","toolName":"search_repositories",\
             "arguments":{"query":"mask-dann in:name"}},"message":""}
            """);

        ExternalMcpAgentDecision decision = plannerService.decide(
            request(), "planning", tools(), List.of(), 2, 4);

        assertEquals("call_tool", decision.getStatus());
        assertEquals("search_repositories", decision.getCall().getToolName());
        assertEquals("mask-dann in:name", decision.getCall().getArguments().get("query"));
    }

    @Test
    void parsesNeedsConfirmationOptions() {
        when(modelClient.chat(anyString())).thenReturn("""
            {"status":"needs_confirmation","assessment":"多个仓库候选","reasoningSummary":"需要用户确认",\
             "call":null,"message":"你指的是哪个仓库？",\
             "options":[{"id":"repo-1","label":"psanford/httpread","description":"公开仓库",\
             "value":{"owner":"psanford","repo":"httpread"}}]}
            """);

        ExternalMcpAgentDecision decision = plannerService.decide(
            request(), "planning", tools(), List.of(), 2, 4);

        assertEquals("needs_confirmation", decision.getStatus());
        assertEquals(1, decision.getOptions().size());
        assertEquals("psanford/httpread", decision.getOptions().get(0).getLabel());
        assertEquals("psanford", decision.getOptions().get(0).getValue().get("owner"));
    }

    @Test
    void promptContainsObservationsAndRequiresDynamicReflection() {
        when(modelClient.chat(anyString())).thenReturn(
            "{\"status\":\"complete\",\"assessment\":\"done\",\"call\":null}");
        ExternalMcpAgentObservation observation = new ExternalMcpAgentObservation(
            1,
            call("get_file_contents", Map.of("owner", "LLhamster", "repo", "mask-dann")),
            ExternalMcpCallResult.failure("github", "get_file_contents", "repository not found"));

        plannerService.decide(request(), "planning", tools(), List.of(observation), 2, 4);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(modelClient).chat(prompt.capture());
        assertTrue(prompt.getValue().contains("repository not found"));
        assertTrue(prompt.getValue().contains("不要依赖固定工具顺序"));
        assertTrue(prompt.getValue().contains("search_repositories"));
        assertTrue(prompt.getValue().contains("剩余工具调用额度：4"));
        assertTrue(prompt.getValue().contains("资源标识必须来自用户明确提供的值"));
        assertTrue(prompt.getValue().contains("full_name"));
        assertTrue(prompt.getValue().contains("代码搜索不能代替仓库发现"));
    }

    @Test
    void invalidJsonReturnsFailedDecision() {
        when(modelClient.chat(anyString())).thenReturn("not json");

        ExternalMcpAgentDecision decision = plannerService.decide(
            request(), "planning", tools(), List.of(), 1, 5);

        assertEquals("failed", decision.getStatus());
        assertEquals("AUTO_PLAN_PARSE_FAILED", decision.getMessage());
    }

    @Test
    void longToolObservationIsTruncatedBeforeNextPlanningCall() {
        when(modelClient.chat(anyString())).thenReturn(
            "{\"status\":\"complete\",\"assessment\":\"done\",\"call\":null}");
        String largeResult = "x".repeat(12000);
        ExternalMcpAgentObservation observation = new ExternalMcpAgentObservation(
            1,
            call("list_commits", Map.of("owner", "LLhamster", "repo", "httpreader")),
            ExternalMcpCallResult.success("github", "list_commits", largeResult));

        plannerService.decide(request(), "planning", tools(), List.of(observation), 2, 4);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(modelClient).chat(prompt.capture());
        assertTrue(prompt.getValue().contains("[tool result truncated]"));
        assertTrue(prompt.getValue().length() < 10000);
    }

    private AiChatRequest request() {
        AiChatRequest request = new AiChatRequest();
        request.setQuestion("读取 mask-dann 仓库 README");
        request.setBookId(1L);
        request.setChapterIndex(1);
        return request;
    }

    private List<Map<String, Object>> tools() {
        return List.of(Map.of(
            "serverName", "github",
            "toolName", "search_repositories",
            "description", "Search repositories",
            "inputSchema", Map.of("type", "object", "required", List.of("query"))));
    }

    private ExternalMcpCall call(String toolName, Map<String, Object> arguments) {
        ExternalMcpCall call = new ExternalMcpCall();
        call.setServerName("github");
        call.setToolName(toolName);
        call.setArguments(arguments);
        return call;
    }
}
