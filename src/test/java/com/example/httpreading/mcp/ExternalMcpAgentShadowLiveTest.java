package com.example.httpreading.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.dto.ExternalMcpCall;
import com.example.httpreading.service.ModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExternalMcpAgentShadowLiveTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ExternalMcpClientService clientService;
    private ExternalMcpAgentService agentService;
    private List<ExternalMcpCall> toolCalls;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Boolean.getBoolean("mcp.agent.shadow.live"),
            "Shadow MCP agent live cases are disabled by default. Run with -Dmcp.agent.shadow.live=true to enable.");
        String apiKey = firstNonBlank(System.getProperty("model.apiKey"), System.getenv("MODEL_API_KEY"));
        assumeTrue(!apiKey.isBlank(),
            "Shadow MCP agent live cases require -Dmodel.apiKey=... or MODEL_API_KEY.");

        ModelClient modelClient = new ModelClient();
        setField(modelClient, "apiKey", apiKey);

        toolCalls = new ArrayList<>();
        clientService = mock(ExternalMcpClientService.class);
        when(clientService.allowedToolDescriptors("github")).thenReturn(githubDescriptors());
        when(clientService.isToolAllowed(anyString(), anyString())).thenAnswer(invocation ->
            "github".equals(invocation.getArgument(0))
                && List.of("search_repositories", "get_repo", "fetch_file", "create_file")
                .contains(invocation.getArgument(1)));
        when(clientService.callTool(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            ExternalMcpCall call = invocation.getArgument(0);
            toolCalls.add(copyCall(call));
            return fakeGitHubResult(call);
        });

        agentService = new ExternalMcpAgentService(
            clientService,
            new ExternalMcpToolPlannerService(modelClient, objectMapper),
            mock(ExternalMcpServerRouterService.class),
            objectMapper,
            new PendingMcpInteractionStore());
    }

    @Test
    void shadowLiveShouldSearchRepositoryThenComplete() {
        ExternalMcpAgentResult result = agentService.execute(
            request("使用 GitHub 搜索 httpreading 的项目"),
            "Planner selected github because the user needs repository search.",
            "github");

        assertEquals("completed", result.getStatus());
        assertTrue(result.getRefs().stream().anyMatch(ref -> ref.contains("MCP_ROUTE github")));
        assertTrue(result.getRefs().stream().anyMatch(ref -> ref.contains("AUTO_ROUND")));
        assertFalse(toolCalls.isEmpty());
        assertEquals("search_repositories", toolCalls.get(0).getToolName());
        assertFalse(calledTool("create_file"));
    }

    @Test
    void shadowLiveShouldAskConfirmationWhenMultipleCandidates() {
        ExternalMcpAgentResult result = agentService.execute(
            request("使用 GitHub 搜索 reading assistant"),
            "Planner selected github because the user needs repository search.",
            "github");

        assertEquals("needs_confirmation", result.getStatus());
        assertNotNull(result.getInteraction());
        assertTrue(result.getInteraction().getOptions().size() <= 3);
        assertTrue(result.getInteraction().getOptions().stream()
            .anyMatch(option -> "a/reading_assistant".equals(option.getLabel())));
        assertTrue(result.getInteraction().getOptions().stream()
            .anyMatch(option -> "b/reading_assistant".equals(option.getLabel())));
        assertTrue(result.getRefs().stream()
            .anyMatch(ref -> ref.contains("AUTO_NEEDS_CONFIRMATION multiple repository candidates")));
    }

    @Test
    void shadowLiveShouldFetchFileWhenOwnerRepoAndPathProvided() {
        ExternalMcpAgentResult result = agentService.execute(
            request("读取 LLhamster/reading_assistant 的 README.md"),
            "Planner selected github because the user asks to read a repository file.",
            "github");

        assertEquals("completed", result.getStatus());
        assertFalse(toolCalls.isEmpty());
        assertFalse("search_repositories".equals(toolCalls.get(0).getToolName()));
        toolCalls.stream()
            .filter(call -> "fetch_file".equals(call.getToolName()))
            .findFirst()
            .ifPresent(call -> {
                assertEquals("LLhamster", call.getArguments().get("owner"));
                assertEquals("reading_assistant", call.getArguments().get("repo"));
                assertTrue(normalize(call.getArguments().get("path")).contains("readme"));
            });
        assertFalse(calledTool("create_file"));
    }

    @Test
    void shadowLiveShouldNotExecuteWriteTool() {
        ExternalMcpAgentResult result = agentService.execute(
            request("帮我在 LLhamster/reading_assistant 创建一个 README.md"),
            "Planner selected github, but this automatic MCP agent is read-only.",
            "github");

        assertFalse(calledTool("create_file"));
        assertFalse(result.getResults().stream()
            .anyMatch(callResult -> "create_file".equals(callResult.getToolName()) && callResult.isOk()));
        result.getResults().stream()
            .filter(callResult -> "create_file".equals(callResult.getToolName()))
            .filter(callResult -> !callResult.isOk())
            .forEach(callResult -> assertTrue(callResult.getError().contains("自动 MCP Agent 禁止调用写操作工具")));
    }

    private ExternalMcpCallResult fakeGitHubResult(ExternalMcpCall call) {
        String toolName = call.getToolName();
        if ("search_repositories".equals(toolName)) {
            String query = normalize(call.getArguments().get("query"));
            if (query.contains("reading assistant") && !query.contains("httpreading")) {
                return ExternalMcpCallResult.success("github", "search_repositories", """
                    {
                      "items": [
                        {"full_name": "a/reading_assistant", "description": "candidate A"},
                        {"full_name": "b/reading_assistant", "description": "candidate B"}
                      ]
                    }
                    """);
            }
            return ExternalMcpCallResult.success("github", "search_repositories", """
                {
                  "items": [
                    {
                      "full_name": "LLhamster/reading_assistant",
                      "description": "AI reading assistant backend"
                    }
                  ]
                }
                """);
        }
        if ("get_repo".equals(toolName)
            && "llhamster".equals(normalize(call.getArguments().get("owner")))
            && "reading_assistant".equals(normalize(call.getArguments().get("repo")))) {
            return ExternalMcpCallResult.success("github", "get_repo", """
                {
                  "full_name": "LLhamster/reading_assistant",
                  "description": "AI reading assistant backend"
                }
                """);
        }
        if ("fetch_file".equals(toolName)) {
            return ExternalMcpCallResult.success("github", "fetch_file", """
                {
                  "path": "README.md",
                  "content": "# reading_assistant\\nAI reading assistant backend"
                }
                """);
        }
        return ExternalMcpCallResult.failure("github", toolName, "fake shadow tool does not implement this call");
    }

    private AiChatRequest request(String question) {
        AiChatRequest request = new AiChatRequest();
        request.setQuestion(question);
        request.setBookId(44L);
        request.setChapterIndex(2);
        request.setUserId("shadow-user");
        request.setSessionId("shadow-session");
        request.setEnableExternalMcp(true);
        return request;
    }

    private List<Map<String, Object>> githubDescriptors() {
        return List.of(
            descriptor("search_repositories", "Search GitHub repositories by keyword.", List.of("query")),
            descriptor("get_repo", "Get repository metadata by owner and repo.", List.of("owner", "repo")),
            descriptor("fetch_file", "Fetch a file from a GitHub repository by owner, repo, path.",
                List.of("owner", "repo", "path")),
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

    private boolean calledTool(String toolName) {
        return toolCalls.stream().anyMatch(call -> toolName.equals(call.getToolName()));
    }

    private ExternalMcpCall copyCall(ExternalMcpCall source) {
        ExternalMcpCall copy = new ExternalMcpCall();
        copy.setServerName(source.getServerName());
        copy.setToolName(source.getToolName());
        copy.setArguments(source.getArguments() == null ? Map.of() : Map.copyOf(source.getArguments()));
        return copy;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private String normalize(Object value) {
        return value == null ? "" : String.valueOf(value).toLowerCase(Locale.ROOT);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
