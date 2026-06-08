package com.example.httpreading.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.dto.ExternalMcpCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExternalMcpAgentServiceTest {
    @Mock
    private ExternalMcpClientService clientService;
    @Mock
    private ExternalMcpToolPlannerService plannerService;
    @Mock
    private ExternalMcpServerRouterService routerService;

    private ExternalMcpAgentService agentService;

    @BeforeEach
    void setUp() {
        agentService = new ExternalMcpAgentService(
            clientService,
            plannerService,
            routerService,
            new ObjectMapper(),
            new PendingMcpInteractionStore());
        org.mockito.Mockito.lenient().when(clientService.routableServers()).thenReturn(List.of(server("github")));
        org.mockito.Mockito.lenient().when(routerService.route(any(), anyString(), anyList()))
            .thenReturn(ExternalMcpServerRoute.use("github", "GitHub 相关问题"));
        org.mockito.Mockito.lenient().when(clientService.allowedToolDescriptors("github")).thenReturn(descriptors());
    }

    @Test
    void routeSkipDoesNotCallPlannerOrTools() {
        when(routerService.route(any(), anyString(), anyList()))
            .thenReturn(ExternalMcpServerRoute.skip("问题不需要外部 MCP 工具"));

        ExternalMcpAgentResult result = agentService.execute(request(), "planning");

        assertEquals("completed", result.getStatus());
        assertTrue(result.getRefs().stream().anyMatch(ref -> ref.contains("MCP_ROUTE_SKIP")));
        verify(plannerService, never()).decide(any(), anyString(), anyList(), anyList(), anyInt(), anyInt());
        verify(clientService, never()).callTool(any());
        verify(clientService, never()).allowedToolDescriptors("github");
    }

    @Test
    void routeSelectionPassesOnlySelectedServerToolsToPlanner() {
        when(clientService.allowedToolDescriptors("github")).thenReturn(List.of(
            descriptor("search_repositories", List.of("query"))));
        ExternalMcpCall search = call("search_repositories", Map.of("query", "repo"));
        when(plannerService.decide(any(), anyString(), anyList(), anyList(), anyInt(), anyInt()))
            .thenReturn(decision(search, "搜索仓库"))
            .thenReturn(new ExternalMcpAgentDecision("complete", "完成", "", null, ""));
        when(clientService.isToolAllowed(anyString(), anyString())).thenReturn(true);
        when(clientService.callTool(any())).thenReturn(
            ExternalMcpCallResult.success("github", "search_repositories", "[]"));

        agentService.execute(request(), "planning");

        ArgumentCaptor<List<Map<String, Object>>> tools = ArgumentCaptor.forClass(List.class);
        verify(plannerService, org.mockito.Mockito.atLeastOnce())
            .decide(any(), anyString(), tools.capture(), anyList(), anyInt(), anyInt());
        assertEquals(1, tools.getValue().size());
        assertEquals("github", tools.getValue().get(0).get("serverName"));
        assertEquals("search_repositories", tools.getValue().get(0).get("toolName"));
    }

    @Test
    void dynamicallyRecoversFromFailedReadBySearchingThenReadingResolvedRepository() {
        ExternalMcpCall failedRead = call("get_file_contents",
            Map.of("owner", "LLhamster", "repo", "mask-dann", "path", "README.md"));
        ExternalMcpCall search = call("search_repositories",
            Map.of("query", "mask-dann-semg-gesture-recognization in:name user:LLhamster"));
        ExternalMcpCall resolvedRead = call("get_file_contents",
            Map.of("owner", "LLhamster", "repo", "mask-dann-semg-gesture-recognization", "path", "README.md"));
        when(plannerService.decide(any(), anyString(), anyList(), anyList(), anyInt(), anyInt()))
            .thenReturn(decision(failedRead, "尝试用户给出的仓库名"))
            .thenReturn(decision(search, "读取失败，搜索相似仓库"))
            .thenReturn(decision(resolvedRead, "使用搜索结果中的完整仓库名"))
            .thenReturn(new ExternalMcpAgentDecision("complete", "README 已取得", "目标完成", null, ""));
        when(clientService.isToolAllowed(anyString(), anyString())).thenReturn(true);
        when(clientService.callTool(any()))
            .thenReturn(ExternalMcpCallResult.failure("github", "get_file_contents", "repository not found"))
            .thenReturn(ExternalMcpCallResult.success("github", "search_repositories",
                "{\"full_name\":\"LLhamster/mask-dann-semg-gesture-recognization\"}"))
            .thenReturn(ExternalMcpCallResult.success("github", "get_file_contents", "README content"));

        ExternalMcpAgentResult result = agentService.execute(request(), "planning");

        assertEquals(3, result.getResults().size());
        assertTrue(result.getRefs().stream().anyMatch(ref -> ref.contains("search_repositories")));
        assertTrue(result.getRefs().stream().anyMatch(ref -> ref.startsWith("AUTO_COMPLETE")));
        ArgumentCaptor<ExternalMcpCall> calls = ArgumentCaptor.forClass(ExternalMcpCall.class);
        verify(clientService, org.mockito.Mockito.times(3)).callTool(calls.capture());
        assertEquals("mask-dann-semg-gesture-recognization",
            calls.getAllValues().get(2).getArguments().get("repo"));
    }

    @Test
    void missingRequiredArgumentsAreReturnedAsObservationForNextRound() {
        ExternalMcpCall invalid = call("search_repositories", Map.of());
        when(plannerService.decide(any(), anyString(), anyList(), anyList(), anyInt(), anyInt()))
            .thenReturn(decision(invalid, "搜索仓库"))
            .thenReturn(new ExternalMcpAgentDecision("failed", "参数无法修正", "", null, "stop"));
        when(clientService.isToolAllowed("github", "search_repositories")).thenReturn(true);

        ExternalMcpAgentResult result = agentService.execute(request(), "planning");

        assertTrue(result.getRefs().stream().anyMatch(ref -> ref.contains("缺少必填参数: query")));
        verify(clientService, never()).callTool(any());
    }

    @Test
    void duplicateCallIsBlockedWithoutExecutingTwice() {
        ExternalMcpCall search = call("search_repositories", Map.of("query", "mask-dann in:name"));
        when(plannerService.decide(any(), anyString(), anyList(), anyList(), anyInt(), anyInt()))
            .thenReturn(decision(search, "搜索"))
            .thenReturn(decision(search, "重复搜索"))
            .thenReturn(new ExternalMcpAgentDecision("failed", "没有其他方法", "", null, "stop"));
        when(clientService.isToolAllowed(anyString(), anyString())).thenReturn(true);
        when(clientService.callTool(any())).thenReturn(
            ExternalMcpCallResult.success("github", "search_repositories", "[]"));

        ExternalMcpAgentResult result = agentService.execute(request(), "planning");

        verify(clientService, org.mockito.Mockito.times(1)).callTool(any());
        assertTrue(result.getRefs().stream().anyMatch(ref -> ref.contains("duplicate call blocked")));
    }

    @Test
    void repositoryTargetThatReturnedNotFoundCannotBeRetriedWithDifferentPagination() {
        ExternalMcpCall first = call("list_commits",
            Map.of("owner", "httpread", "repo", "httpread", "perPage", 100));
        ExternalMcpCall retry = call("list_commits",
            Map.of("owner", "httpread", "repo", "httpread", "perPage", 30));
        when(clientService.allowedToolDescriptors("github")).thenReturn(List.of(
            descriptor("list_commits", List.of("owner", "repo")),
            descriptor("search_repositories", List.of("query"))));
        when(plannerService.decide(any(), anyString(), anyList(), anyList(), anyInt(), anyInt()))
            .thenReturn(decision(first, "尝试仓库"))
            .thenReturn(decision(retry, "修改分页后重试"))
            .thenReturn(new ExternalMcpAgentDecision("failed", "必须重新解析仓库", "", null, "stop"));
        when(clientService.isToolAllowed(anyString(), anyString())).thenReturn(true);
        when(clientService.callTool(any())).thenReturn(
            ExternalMcpCallResult.failure("github", "list_commits", "404 Not Found"));

        ExternalMcpAgentResult result = agentService.execute(request(), "planning");

        verify(clientService, org.mockito.Mockito.times(1)).callTool(any());
        assertTrue(result.getRefs().stream().anyMatch(ref -> ref.contains("此前已返回 Not Found")));
    }

    @Test
    void writeToolIsBlockedEvenWhenConfiguredAsAllowed() {
        ExternalMcpCall write = call("create_issue", Map.of("title", "x"));
        when(clientService.allowedToolDescriptors("github")).thenReturn(List.of(descriptor("create_issue", List.of("title"))));
        when(clientService.isToolAllowed("github", "create_issue")).thenReturn(true);
        when(plannerService.decide(any(), anyString(), anyList(), anyList(), anyInt(), anyInt()))
            .thenReturn(decision(write, "创建 issue"))
            .thenReturn(new ExternalMcpAgentDecision("failed", "被安全策略拒绝", "", null, "stop"));

        ExternalMcpAgentResult result = agentService.execute(request(), "planning");

        assertTrue(result.getRefs().stream().anyMatch(ref -> ref.contains("禁止调用写操作工具")));
        verify(clientService, never()).callTool(any());
    }

    @Test
    void nonAllowedToolIsReturnedAsObservationAndNotExecuted() {
        ExternalMcpCall hidden = call("get_repository_tree", Map.of("owner", "x", "repo", "y"));
        when(plannerService.decide(any(), anyString(), anyList(), anyList(), anyInt(), anyInt()))
            .thenReturn(decision(hidden, "尝试未开放工具"))
            .thenReturn(new ExternalMcpAgentDecision("failed", "没有其他方法", "", null, "stop"));

        ExternalMcpAgentResult result = agentService.execute(request(), "planning");

        assertTrue(result.getRefs().stream().anyMatch(ref -> ref.contains("不在 allowedTools")));
        verify(clientService, never()).callTool(any());
    }

    @Test
    void stopsAfterFiveReflectionRounds() {
        when(plannerService.decide(any(), anyString(), anyList(), anyList(), anyInt(), anyInt()))
            .thenAnswer(invocation -> {
                int round = invocation.getArgument(4);
                return decision(call("search_repositories", Map.of("query", "query-" + round)), "继续探索");
            });
        when(clientService.isToolAllowed(anyString(), anyString())).thenReturn(true);
        when(clientService.callTool(any())).thenReturn(
            ExternalMcpCallResult.success("github", "search_repositories", "[]"));

        ExternalMcpAgentResult result = agentService.execute(request(), "planning");

        verify(clientService, org.mockito.Mockito.times(5)).callTool(any());
        assertEquals("AUTO_LIMIT_REACHED", result.getRefs().get(result.getRefs().size() - 1));
    }

    @Test
    void ambiguityStopsWithConfirmationGuidance() {
        when(plannerService.decide(any(), anyString(), anyList(), anyList(), anyInt(), anyInt()))
            .thenReturn(new ExternalMcpAgentDecision(
                "needs_confirmation", "存在两个相似仓库", "", null, "请选择 repo-a 或 repo-b",
                List.of(new ExternalMcpAgentOption(
                    "repo-1", "repo-a", "候选 A", Map.of("owner", "a", "repo", "repo-a")))));

        ExternalMcpAgentResult result = agentService.execute(request(), "planning");

        assertEquals("needs_confirmation", result.getStatus());
        assertEquals("请选择 repo-a 或 repo-b", result.getGuidance());
        assertEquals("repo-a", result.getInteraction().getOptions().get(0).getLabel());
        assertTrue(result.getRefs().stream().anyMatch(ref -> ref.startsWith("AUTO_NEEDS_CONFIRMATION")));
        verify(clientService, never()).callTool(any());
    }

    @Test
    void resumesFromSelectedOptionWithoutRepeatingPreviousSearch() {
        when(clientService.allowedToolDescriptors("github")).thenReturn(List.of(
            descriptor("search_repositories", List.of("query")),
            descriptor("list_commits", List.of("owner", "repo"))));
        ExternalMcpCall search = call("search_repositories", Map.of("query", "httpread in:name"));
        ExternalMcpCall listCommits = call("list_commits", Map.of("owner", "psanford", "repo", "httpread"));
        when(plannerService.decide(any(), anyString(), anyList(), anyList(), anyInt(), anyInt()))
            .thenReturn(decision(search, "搜索仓库"))
            .thenReturn(new ExternalMcpAgentDecision(
                "needs_confirmation", "存在多个 httpread 仓库", "", null, "你指的是哪个仓库？",
                List.of(new ExternalMcpAgentOption(
                    "repo-1", "psanford/httpread", "公开仓库",
                    Map.of("owner", "psanford", "repo", "httpread")))))
            .thenReturn(decision(listCommits, "使用用户确认的仓库"))
            .thenReturn(new ExternalMcpAgentDecision("complete", "提交记录已取得", "", null, ""));
        when(clientService.isToolAllowed(anyString(), anyString())).thenReturn(true);
        when(clientService.callTool(any()))
            .thenReturn(ExternalMcpCallResult.success("github", "search_repositories",
                "[{\"full_name\":\"psanford/httpread\"}]"))
            .thenReturn(ExternalMcpCallResult.success("github", "list_commits", "[{\"sha\":\"abc\"}]"));

        ExternalMcpAgentResult paused = agentService.execute(request(), "planning");
        AiChatRequest confirmation = request();
        confirmation.setConfirmationId(paused.getInteraction().getConfirmationId());
        confirmation.setSelectedOptionId("repo-1");
        confirmation.setQuestion("psanford/httpread");

        ExternalMcpAgentResult completed = agentService.resume(confirmation);

        assertEquals("completed", completed.getStatus());
        assertTrue(completed.getRefs().stream().anyMatch(ref -> ref.startsWith("AUTO_CONFIRMATION")));
        ArgumentCaptor<ExternalMcpCall> calls = ArgumentCaptor.forClass(ExternalMcpCall.class);
        verify(clientService, org.mockito.Mockito.times(2)).callTool(calls.capture());
        assertEquals("search_repositories", calls.getAllValues().get(0).getToolName());
        assertEquals("list_commits", calls.getAllValues().get(1).getToolName());
        assertEquals("psanford", calls.getAllValues().get(1).getArguments().get("owner"));
    }

    @Test
    void ambiguousRepositorySearchResultForcesConfirmationBeforeNextPlannerRound() {
        when(clientService.allowedToolDescriptors("github")).thenReturn(List.of(
            descriptor("search_repositories", List.of("query")),
            descriptor("list_commits", List.of("owner", "repo"))));
        ExternalMcpCall search = call("search_repositories", Map.of("query", "httpread in:name"));
        ExternalMcpCall listCommits = call("list_commits", Map.of("owner", "psanford", "repo", "httpread"));
        when(plannerService.decide(any(), anyString(), anyList(), anyList(), anyInt(), anyInt()))
            .thenReturn(decision(search, "搜索仓库"))
            .thenReturn(decision(listCommits, "模型想继续查询第一个候选"));
        when(clientService.isToolAllowed(anyString(), anyString())).thenReturn(true);
        when(clientService.callTool(any())).thenReturn(ExternalMcpCallResult.success(
            "github",
            "search_repositories",
            """
                {"total_count":2,"items":[
                  {"full_name":"psanford/httpread","description":"HTTP parser"},
                  {"full_name":"Heng-Bian/httpread","description":"another httpread"}
                ]}
                """));

        ExternalMcpAgentResult result = agentService.execute(request(), "planning");

        assertEquals("needs_confirmation", result.getStatus());
        assertEquals(2, result.getInteraction().getOptions().size());
        assertEquals("psanford/httpread", result.getInteraction().getOptions().get(0).getLabel());
        assertTrue(result.getRefs().stream().anyMatch(ref -> ref.contains("multiple repository candidates")));
        verify(clientService, org.mockito.Mockito.times(1)).callTool(any());
        verify(plannerService, org.mockito.Mockito.times(1))
            .decide(any(), anyString(), anyList(), anyList(), anyInt(), anyInt());
    }

    @Test
    void selectedRepositoryOverridesLaterModelRepositoryGuess() {
        when(clientService.allowedToolDescriptors("github")).thenReturn(List.of(
            descriptor("search_repositories", List.of("query")),
            descriptor("list_commits", List.of("owner", "repo"))));
        ExternalMcpCall search = call("search_repositories", Map.of("query", "httpread in:name"));
        ExternalMcpCall wrongListCommits = call("list_commits", Map.of("owner", "psanford", "repo", "httpread"));
        when(plannerService.decide(any(), anyString(), anyList(), anyList(), anyInt(), anyInt()))
            .thenReturn(decision(search, "搜索仓库"))
            .thenReturn(decision(wrongListCommits, "模型仍然沿用错误候选"))
            .thenReturn(new ExternalMcpAgentDecision("complete", "提交记录已取得", "", null, ""));
        when(clientService.isToolAllowed(anyString(), anyString())).thenReturn(true);
        when(clientService.callTool(any()))
            .thenReturn(ExternalMcpCallResult.success(
                "github",
                "search_repositories",
                """
                    {"items":[
                      {"full_name":"psanford/httpread","description":"HTTP parser"},
                      {"full_name":"Heng-Bian/httpread","description":"Rust package"}
                    ]}
                    """))
            .thenReturn(ExternalMcpCallResult.success("github", "list_commits", "[{\"sha\":\"abc\"}]"));

        ExternalMcpAgentResult paused = agentService.execute(request(), "planning");
        AiChatRequest confirmation = request();
        confirmation.setConfirmationId(paused.getInteraction().getConfirmationId());
        confirmation.setSelectedOptionId("repo-2");
        confirmation.setQuestion("Heng-Bian/httpread");

        ExternalMcpAgentResult completed = agentService.resume(confirmation);

        assertEquals("completed", completed.getStatus());
        assertTrue(completed.getRefs().stream()
            .anyMatch(ref -> ref.contains("AUTO_REWRITE_REPOSITORY_TARGET psanford/httpread -> Heng-Bian/httpread")));
        ArgumentCaptor<ExternalMcpCall> calls = ArgumentCaptor.forClass(ExternalMcpCall.class);
        verify(clientService, org.mockito.Mockito.times(2)).callTool(calls.capture());
        ExternalMcpCall actualCommitCall = calls.getAllValues().get(1);
        assertEquals("Heng-Bian", actualCommitCall.getArguments().get("owner"));
        assertEquals("httpread", actualCommitCall.getArguments().get("repo"));
    }

    private ExternalMcpAgentDecision decision(ExternalMcpCall call, String reason) {
        return new ExternalMcpAgentDecision("call_tool", "目标尚未完成", reason, call, "");
    }

    private AiChatRequest request() {
        AiChatRequest request = new AiChatRequest();
        request.setQuestion("读取 mask-dann 仓库 README");
        request.setBookId(1L);
        request.setChapterIndex(1);
        return request;
    }

    private ExternalMcpCall call(String toolName, Map<String, Object> arguments) {
        ExternalMcpCall call = new ExternalMcpCall();
        call.setServerName("github");
        call.setToolName(toolName);
        call.setArguments(arguments);
        return call;
    }

    private Map<String, Object> server(String name) {
        return Map.of(
            "name", name,
            "description", "GitHub 仓库资料",
            "allowedTools", List.of("search_repositories", "get_file_contents"));
    }

    private List<Map<String, Object>> descriptors() {
        return List.of(
            descriptor("get_file_contents", List.of("owner", "repo")),
            descriptor("search_repositories", List.of("query")),
            descriptor("get_me", List.of()));
    }

    private Map<String, Object> descriptor(String toolName, List<String> required) {
        return Map.of(
            "serverName", "github",
            "toolName", toolName,
            "inputSchema", Map.of("type", "object", "required", required));
    }
}
