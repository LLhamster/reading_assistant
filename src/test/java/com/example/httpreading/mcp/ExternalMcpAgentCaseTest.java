package com.example.httpreading.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
class ExternalMcpAgentCaseTest {
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
        org.mockito.Mockito.lenient().when(clientService.allowedToolDescriptors("github"))
            .thenReturn(githubDescriptors());
        org.mockito.Mockito.lenient().when(clientService.isToolAllowed(anyString(), anyString()))
            .thenAnswer(invocation -> "github".equals(invocation.getArgument(0))
                && List.of("search_repositories", "fetch_file", "get_repo", "create_file")
                .contains(invocation.getArgument(1)));
    }

    @Test
    void shouldSearchRepositoriesWhenOnlyRepoKeywordProvided() {
        ExternalMcpCall search = call("search_repositories", Map.of("query", "httpreading"));
        when(plannerService.decide(any(), anyString(), anyList(), anyList(), anyInt(), anyInt()))
            .thenReturn(decision(search, "仓库简称需要先搜索"))
            .thenReturn(complete("找到单个候选"));
        when(clientService.callTool(any())).thenReturn(ExternalMcpCallResult.success(
            "github",
            "search_repositories",
            """
                {"items":[{"full_name":"LLhamster/reading_assistant","description":"AI reading assistant"}]}
                """));

        ExternalMcpAgentResult result = agentService.execute(request("使用 GitHub 搜索 httpreading 的项目"),
            planningContext(), "github");

        assertEquals("completed", result.getStatus());
        verify(clientService).allowedToolDescriptors("github");
        ArgumentCaptor<ExternalMcpCall> call = ArgumentCaptor.forClass(ExternalMcpCall.class);
        verify(clientService).callTool(call.capture());
        assertEquals("github", call.getValue().getServerName());
        assertEquals("search_repositories", call.getValue().getToolName());
        assertTrue(result.getRefs().stream().anyMatch(ref -> ref.contains("MCP_ROUTE github")));
        assertTrue(result.getRefs().stream()
            .anyMatch(ref -> ref.contains("AUTO_ROUND 1 CALL github/search_repositories")));
        assertTrue(result.getRefs().stream().anyMatch(ref -> ref.startsWith("AUTO_COMPLETE")));
    }

    @Test
    void shouldRequireConfirmationWhenRepositorySearchReturnsMultipleCandidates() {
        ExternalMcpCall search = call("search_repositories", Map.of("query", "reading assistant"));
        when(plannerService.decide(any(), anyString(), anyList(), anyList(), anyInt(), anyInt()))
            .thenReturn(decision(search, "搜索候选仓库"));
        when(clientService.callTool(any())).thenReturn(ExternalMcpCallResult.success(
            "github",
            "search_repositories",
            """
                {"items":[
                  {"full_name":"a/reading_assistant","description":"candidate A"},
                  {"full_name":"b/reading_assistant","description":"candidate B"}
                ]}
                """));

        ExternalMcpAgentResult result = agentService.execute(request("使用 GitHub 搜索 reading assistant"),
            planningContext(), "github");

        assertEquals("needs_confirmation", result.getStatus());
        assertNotNull(result.getInteraction());
        assertTrue(result.getInteraction().getOptions().size() <= 3);
        assertTrue(result.getInteraction().getOptions().stream()
            .anyMatch(option -> "a/reading_assistant".equals(option.getLabel())));
        assertTrue(result.getInteraction().getOptions().stream()
            .anyMatch(option -> "b/reading_assistant".equals(option.getLabel())));
        assertTrue(result.getRefs().stream()
            .anyMatch(ref -> ref.contains("AUTO_NEEDS_CONFIRMATION multiple repository candidates")));
        verify(clientService).callTool(any());
        verify(plannerService, org.mockito.Mockito.times(1))
            .decide(any(), anyString(), anyList(), anyList(), anyInt(), anyInt());
    }

    @Test
    void shouldBlockWriteToolEvenIfPlannerSelectsIt() {
        ExternalMcpCall write = call("create_file", Map.of(
            "owner", "LLhamster",
            "repo", "reading_assistant",
            "path", "README.md",
            "content", "test"));
        when(plannerService.decide(any(), anyString(), anyList(), anyList(), anyInt(), anyInt()))
            .thenReturn(decision(write, "尝试写 README"))
            .thenReturn(failed("写操作被拒绝"));

        ExternalMcpAgentResult result = agentService.execute(request("帮我在 GitHub 仓库里创建一个 README"),
            planningContext(), "github");

        verify(clientService, never()).callTool(any());
        assertTrue(result.getRefs().stream().anyMatch(ref -> ref.contains("AUTO_OBSERVE 1 FAIL")));
        assertTrue(result.getRefs().stream().anyMatch(ref -> ref.contains("自动 MCP Agent 禁止调用写操作工具")));
    }

    @Test
    void shouldRejectToolCallWhenRequiredArgumentsMissing() {
        ExternalMcpCall fetch = call("fetch_file", Map.of(
            "owner", "LLhamster",
            "repo", "reading_assistant"));
        when(plannerService.decide(any(), anyString(), anyList(), anyList(), anyInt(), anyInt()))
            .thenReturn(decision(fetch, "读取 README"))
            .thenReturn(failed("参数无法修正"));

        ExternalMcpAgentResult result = agentService.execute(request("读取 README"), planningContext(), "github");

        verify(clientService, never()).callTool(any());
        assertTrue(result.getRefs().stream().anyMatch(ref -> ref.contains("AUTO_OBSERVE 1 FAIL")));
        assertTrue(result.getRefs().stream().anyMatch(ref -> ref.contains("缺少必填参数: path")));
        ArgumentCaptor<List<ExternalMcpAgentObservation>> observations = ArgumentCaptor.forClass(List.class);
        verify(plannerService, org.mockito.Mockito.times(2))
            .decide(any(), anyString(), anyList(), observations.capture(), anyInt(), anyInt());
        assertTrue(observations.getAllValues().get(1).stream()
            .anyMatch(observation -> !observation.result().isOk()
                && observation.result().getError().contains("缺少必填参数: path")));
    }

    @Test
    void shouldBlockDuplicateToolCall() {
        ExternalMcpCall search = call("search_repositories", Map.of("query", "httpreading"));
        when(plannerService.decide(any(), anyString(), anyList(), anyList(), anyInt(), anyInt()))
            .thenReturn(decision(search, "首次搜索"))
            .thenReturn(decision(search, "重复搜索"))
            .thenReturn(failed("没有其他方法"));
        when(clientService.callTool(any())).thenReturn(ExternalMcpCallResult.success(
            "github", "search_repositories", "{\"items\":[]}"));

        ExternalMcpAgentResult result = agentService.execute(request("使用 GitHub 搜索 httpreading 的项目"),
            planningContext(), "github");

        verify(clientService, org.mockito.Mockito.times(1)).callTool(any());
        assertTrue(result.getRefs().stream().anyMatch(ref -> ref.contains("duplicate call blocked")));
        ArgumentCaptor<List<ExternalMcpAgentObservation>> observations = ArgumentCaptor.forClass(List.class);
        verify(plannerService, org.mockito.Mockito.times(3))
            .decide(any(), anyString(), anyList(), observations.capture(), anyInt(), anyInt());
        assertTrue(observations.getAllValues().get(2).stream()
            .anyMatch(observation -> !observation.result().isOk()
                && observation.result().getError().contains("重复工具调用已被阻止")));
    }

    @Test
    void shouldRejectPreviouslyNotFoundRepositoryTarget() {
        ExternalMcpCall first = call("get_repo", Map.of("owner", "wrong", "repo", "missing"));
        ExternalMcpCall retry = call("get_repo", Map.of("owner", "wrong", "repo", "missing"));
        when(plannerService.decide(any(), anyString(), anyList(), anyList(), anyInt(), anyInt()))
            .thenReturn(decision(first, "尝试仓库"))
            .thenReturn(decision(retry, "再次尝试同一仓库"))
            .thenReturn(failed("必须重新解析仓库"));
        when(clientService.callTool(any())).thenReturn(ExternalMcpCallResult.failure(
            "github", "get_repo", "404 Repository not found"));

        ExternalMcpAgentResult result = agentService.execute(request("查询 wrong/missing"), planningContext(), "github");

        verify(clientService, org.mockito.Mockito.times(1)).callTool(any());
        assertTrue(result.getRefs().stream().anyMatch(ref -> ref.contains("该仓库目标此前已返回 Not Found")));
    }

    private ExternalMcpAgentDecision decision(ExternalMcpCall call, String reason) {
        return new ExternalMcpAgentDecision("call_tool", "目标尚未完成", reason, call, "");
    }

    private ExternalMcpAgentDecision complete(String message) {
        return new ExternalMcpAgentDecision("complete", message, "", null, "");
    }

    private ExternalMcpAgentDecision failed(String message) {
        return new ExternalMcpAgentDecision("failed", message, "", null, message);
    }

    private AiChatRequest request(String question) {
        AiChatRequest request = new AiChatRequest();
        request.setQuestion(question);
        request.setBookId(44L);
        request.setChapterIndex(2);
        request.setUserId("u1");
        request.setSessionId("s1");
        request.setEnableExternalMcp(true);
        return request;
    }

    private String planningContext() {
        return "allowedTools=[mcp.server:github]";
    }

    private ExternalMcpCall call(String toolName, Map<String, Object> arguments) {
        ExternalMcpCall call = new ExternalMcpCall();
        call.setServerName("github");
        call.setToolName(toolName);
        call.setArguments(arguments);
        return call;
    }

    private List<Map<String, Object>> githubDescriptors() {
        return List.of(
            descriptor("search_repositories", "Search GitHub repositories by keyword.", List.of("query")),
            descriptor("fetch_file", "Fetch a file from a GitHub repository by owner, repo, path.",
                List.of("owner", "repo", "path")),
            descriptor("get_repo", "Get repository metadata by owner and repo.", List.of("owner", "repo")),
            descriptor("create_file", "Create or update a file in a repository.",
                List.of("owner", "repo", "path", "content")));
    }

    private Map<String, Object> descriptor(String toolName, String description, List<String> required) {
        return Map.of(
            "serverName", "github",
            "toolName", toolName,
            "description", description,
            "inputSchema", Map.of("type", "object", "required", required));
    }
}
