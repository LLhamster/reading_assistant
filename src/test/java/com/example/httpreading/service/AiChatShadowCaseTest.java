package com.example.httpreading.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.example.httpreading.context.manager.DefaultContextManager;
import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.dto.AiChatResponse;
import com.example.httpreading.dto.ExternalMcpCall;
import com.example.httpreading.mcp.ExternalMcpAgentService;
import com.example.httpreading.mcp.ExternalMcpCallResult;
import com.example.httpreading.mcp.ExternalMcpClientService;
import com.example.httpreading.mcp.ExternalMcpServerRouterService;
import com.example.httpreading.mcp.ExternalMcpToolPlannerService;
import com.example.httpreading.mcp.PendingMcpInteractionStore;
import com.example.httpreading.service.ai.EvidenceAggregator;
import com.example.httpreading.service.ai.FinalAnswerService;
import com.example.httpreading.service.ai.LocalMcpToolAdapter;
import com.example.httpreading.service.ai.McpToolOrchestrator;
import com.example.httpreading.service.ai.MemoryWriter;
import com.example.httpreading.service.ai.PlanValidator;
import com.example.httpreading.service.ai.PlannerPromptBuilder;
import com.example.httpreading.service.ai.PlannerService;
import com.example.httpreading.service.ai.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AiChatShadowCaseTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ModelClient plannerModelClient;
    private ModelClient toolPlannerModelClient;
    private ModelClient finalAnswerModelClient;
    private ExternalMcpClientService externalMcpClientService;
    private LocalMcpToolAdapter localMcpToolAdapter;
    private AgentMemoryService agentMemoryService;
    private AiChatService aiChatService;

    @BeforeEach
    void setUp() {
        plannerModelClient = mock(ModelClient.class);
        toolPlannerModelClient = mock(ModelClient.class);
        finalAnswerModelClient = mock(ModelClient.class);
        externalMcpClientService = mock(ExternalMcpClientService.class);
        localMcpToolAdapter = mock(LocalMcpToolAdapter.class);
        agentMemoryService = mock(AgentMemoryService.class);

        ExternalMcpAgentService externalMcpAgentService = new ExternalMcpAgentService(
            externalMcpClientService,
            new ExternalMcpToolPlannerService(toolPlannerModelClient, objectMapper),
            mock(ExternalMcpServerRouterService.class),
            objectMapper,
            new PendingMcpInteractionStore());
        PlannerService plannerService = new PlannerService(
            plannerModelClient,
            objectMapper,
            new PlannerPromptBuilder(externalMcpClientService),
            new PlanValidator(new ToolRegistry(), externalMcpClientService),
            externalMcpClientService);
        McpToolOrchestrator orchestrator = new McpToolOrchestrator(
            localMcpToolAdapter,
            externalMcpClientService,
            externalMcpAgentService);

        aiChatService = new AiChatService(
            mock(RagService.class),
            new DefaultContextManager(),
            externalMcpAgentService,
            plannerService,
            orchestrator,
            new EvidenceAggregator(objectMapper),
            new FinalAnswerService(finalAnswerModelClient),
            new MemoryWriter(agentMemoryService));
    }

    @Test
    void shouldAnswerGitHubSearchFromMcpEvidence() {
        enableGithubServer();
        when(plannerModelClient.chat(anyString())).thenReturn(
            githubBoundedReactPlan("使用 GitHub 搜索 httpreading 的项目"));
        when(toolPlannerModelClient.chat(anyString()))
            .thenReturn(searchRepositoriesDecision())
            .thenReturn(completeDecision());
        when(externalMcpClientService.callTool(any())).thenAnswer(invocation ->
            fakeSingleRepositoryResult(invocation.getArgument(0)));
        when(finalAnswerModelClient.chat(anyString())).thenReturn(
            "我在 GitHub MCP 工具结果中找到了 LLhamster/reading_assistant。"
                + "它的描述是 AI reading assistant backend。这个结果来自 GitHub MCP 搜索结果。");

        AiChatRequest request = request("使用 GitHub 搜索 httpreading 的项目");
        AiChatResponse response = aiChatService.chat(request);

        assertEquals("completed", response.getStatus());
        assertTrue(response.getAnswer().contains("LLhamster/reading_assistant"));
        assertTrue(response.getAnswer().contains("AI reading assistant backend"));
        assertTrue(response.getAnswer().contains("GitHub MCP"));
        assertFalse(response.getAnswer().contains("根据书籍资料"));
        assertFalse(response.getAnswer().contains("根据记忆"));
        assertTrue(response.getExternalMcpRefs().contains("OK github/search_repositories"));
        assertTrue(response.getExternalMcpPlanRefs().stream()
            .anyMatch(ref -> ref.contains("MCP_ROUTE github")));
        assertTrue(response.getExternalMcpPlanRefs().stream()
            .anyMatch(ref -> ref.contains("AUTO_ROUND")));
        verifyNoInteractions(localMcpToolAdapter);
        ArgumentCaptor<String> finalPrompt = ArgumentCaptor.forClass(String.class);
        verify(finalAnswerModelClient).chat(finalPrompt.capture());
        assertTrue(finalPrompt.getValue().contains("LLhamster/reading_assistant"));
        assertTrue(finalPrompt.getValue().contains("AI reading assistant backend"));
        assertTrue(finalPrompt.getValue().contains("github/search_repositories"));
        verify(agentMemoryService).rememberWorkingTurn(
            anyString(), anyString(), anyLong(), anyInt(), anyString(), anyString(), anyInt());
    }

    @Test
    void shouldNotUseRagOrMemoryWhenGitHubServerUnavailable() {
        when(externalMcpClientService.routableServers()).thenReturn(List.of());
        when(plannerModelClient.chat(anyString())).thenReturn(githubUnavailablePlan());
        when(finalAnswerModelClient.chat(anyString())).thenReturn(
            "当前没有可用的 GitHub MCP 工具，因此不能执行 GitHub 搜索。"
                + "不能用阅读资料或历史记忆冒充实时 GitHub 结果。");

        AiChatResponse response = aiChatService.chat(request("使用 GitHub 搜索 httpreading 的项目"));

        assertEquals("completed", response.getStatus());
        assertTrue(response.getAnswer().contains("没有可用的 GitHub MCP"));
        assertTrue(response.getAnswer().contains("不能执行 GitHub 搜索"));
        assertFalse(response.getAnswer().contains("我搜索到"));
        assertFalse(response.getAnswer().contains("LLhamster/reading_assistant"));
        assertTrue(response.getExternalMcpPlanRefs().stream()
            .anyMatch(ref -> ref.contains("PLAN_MODE NO_TOOL")));
        verifyNoInteractions(toolPlannerModelClient);
        verify(externalMcpClientService, never()).callTool(any());
        verifyNoInteractions(localMcpToolAdapter);
    }

    @Test
    void shouldAskUserToConfirmWhenMcpNeedsConfirmation() {
        enableGithubServer();
        when(plannerModelClient.chat(anyString())).thenReturn(
            githubBoundedReactPlan("使用 GitHub 搜索 reading assistant"));
        when(toolPlannerModelClient.chat(anyString())).thenReturn(searchRepositoriesDecision());
        when(externalMcpClientService.callTool(any())).thenAnswer(invocation ->
            fakeMultipleRepositoryResult(invocation.getArgument(0)));

        AiChatResponse response = aiChatService.chat(request("使用 GitHub 搜索 reading assistant"));

        assertEquals("needs_confirmation", response.getStatus());
        assertTrue(response.getAnswer().contains("搜索到多个可能的 GitHub 仓库，请选择"));
        assertNotNull(response.getInteraction());
        assertTrue(response.getInteraction().getOptions().size() <= 3);
        assertTrue(response.getInteraction().getOptions().stream()
            .anyMatch(option -> "a/reading_assistant".equals(option.getLabel())));
        assertTrue(response.getInteraction().getOptions().stream()
            .anyMatch(option -> "b/reading_assistant".equals(option.getLabel())));
        assertTrue(response.getExternalMcpPlanRefs().stream()
            .anyMatch(ref -> ref.contains("AUTO_NEEDS_CONFIRMATION multiple repository candidates")));
        verifyNoInteractions(finalAnswerModelClient);
        verifyNoInteractions(agentMemoryService);
        verifyNoInteractions(localMcpToolAdapter);
    }

    private void enableGithubServer() {
        when(externalMcpClientService.routableServers()).thenReturn(List.of(Map.of(
            "name", "github",
            "description", "GitHub repository search",
            "allowedTools", List.of("search_repositories", "get_repo", "fetch_file"))));
        when(externalMcpClientService.allowedToolDescriptors("github")).thenReturn(githubDescriptors());
        when(externalMcpClientService.isToolAllowed(anyString(), anyString())).thenAnswer(invocation ->
            "github".equals(invocation.getArgument(0))
                && List.of("search_repositories", "get_repo", "fetch_file")
                .contains(invocation.getArgument(1)));
    }

    private ExternalMcpCallResult fakeSingleRepositoryResult(ExternalMcpCall call) {
        if (!"search_repositories".equals(call.getToolName())) {
            return ExternalMcpCallResult.failure("github", call.getToolName(), "unexpected fake tool call");
        }
        return ExternalMcpCallResult.success("github", "search_repositories", """
            {
              "items": [
                {
                  "full_name": "LLhamster/reading_assistant",
                  "description": "AI reading assistant backend",
                  "html_url": "https://github.com/LLhamster/reading_assistant"
                }
              ]
            }
            """);
    }

    private ExternalMcpCallResult fakeMultipleRepositoryResult(ExternalMcpCall call) {
        if (!"search_repositories".equals(call.getToolName())) {
            return ExternalMcpCallResult.failure("github", call.getToolName(), "unexpected fake tool call");
        }
        return ExternalMcpCallResult.success("github", "search_repositories", """
            {
              "items": [
                {"full_name": "a/reading_assistant", "description": "candidate A"},
                {"full_name": "b/reading_assistant", "description": "candidate B"}
              ]
            }
            """);
    }

    private List<Map<String, Object>> githubDescriptors() {
        return List.of(
            descriptor("search_repositories", "Search GitHub repositories by keyword.", List.of("query")),
            descriptor("get_repo", "Get repository metadata by owner and repo.", List.of("owner", "repo")),
            descriptor("fetch_file", "Fetch a file from a GitHub repository by owner, repo, path.",
                List.of("owner", "repo", "path")));
    }

    private Map<String, Object> descriptor(String toolName, String description, List<String> required) {
        return Map.of(
            "serverName", "github",
            "toolName", toolName,
            "description", description,
            "inputSchema", Map.of("type", "object", "required", required));
    }

    private AiChatRequest request(String question) {
        AiChatRequest request = new AiChatRequest();
        request.setBookId(44L);
        request.setChapterIndex(2);
        request.setQuestion(question);
        request.setUserId("shadow-user");
        request.setSessionId("shadow-session");
        request.setEnableExternalMcp(true);
        request.setEnableRag(true);
        request.setEnableMemory(true);
        return request;
    }

    private String githubBoundedReactPlan(String standaloneQuestion) {
        return planJson(
            standaloneQuestion,
            "BOUNDED_REACT",
            "[\"mcp.server:github\"]",
            5,
            "通过 GitHub MCP 搜索仓库",
            "ReAct agent 获得足够 GitHub 证据或需要用户确认时停止",
            "严格依据 GitHub MCP 工具结果回答，不要把 RAG 或记忆说成 GitHub 搜索结果。");
    }

    private String githubUnavailablePlan() {
        return planJson(
            "使用 GitHub 搜索 httpreading 的项目",
            "NO_TOOL",
            "[]",
            0,
            "说明当前无法执行 GitHub 搜索",
            "当前没有可用 GitHub MCP server，停止工具调用",
            "明确说明没有可用 GitHub/外部搜索工具，不要用 RAG 或记忆冒充 GitHub 搜索结果。");
    }

    private String planJson(String standaloneQuestion,
                            String executionMode,
                            String allowedTools,
                            int maxSteps,
                            String taskGoal,
                            String stopCondition,
                            String answerGuidance) {
        return """
            {
              "taskType": "TOOL_ACTION",
              "taskTypeReason": "用户明确要求执行 GitHub 仓库搜索。",
              "subIntent": "NONE",
              "standaloneQuestion": "%s",
              "dependsOnContext": false,
              "executionMode": "%s",
              "allowedTools": %s,
              "toolPlan": [],
              "taskGoal": "%s",
              "maxSteps": %d,
              "stopCondition": "%s",
              "answerGuidance": "%s",
              "answerMode": "EXTERNAL_SEARCH_REQUIRED",
              "evidenceStrictness": "STRICT",
              "answerRequirement": {
                "requiresConcreteExample": false,
                "requiresSpecificEntity": false,
                "requiresStorytelling": false,
                "requiresDetailedProcess": false,
                "avoidConceptualOpening": false,
                "avoidRepeatingPreviousExplanation": false,
                "allowModelKnowledge": false,
                "mustDistinguishTextEvidenceAndSupplement": false,
                "avoidRepeatingSourcePhrases": false,
                "minDetailLevel": "MEDIUM"
              },
              "planningReason": "GitHub 搜索必须使用匹配的外部 MCP server，不能使用阅读工具代替。"
            }
            """.formatted(
            standaloneQuestion,
            executionMode,
            allowedTools,
            taskGoal,
            maxSteps,
            stopCondition,
            answerGuidance);
    }

    private String searchRepositoriesDecision() {
        return """
            {
              "status": "call_tool",
              "assessment": "需要搜索仓库",
              "reasoningSummary": "用户要求 GitHub 搜索",
              "call": {
                "serverName": "github",
                "toolName": "search_repositories",
                "arguments": {"query": "httpreading"}
              },
              "message": "",
              "options": []
            }
            """;
    }

    private String completeDecision() {
        return """
            {
              "status": "complete",
              "assessment": "已经获得搜索结果",
              "reasoningSummary": "搜索结果足够回答",
              "call": null,
              "message": "",
              "options": []
            }
            """;
    }
}
