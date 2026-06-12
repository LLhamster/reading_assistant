package com.example.httpreading.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.service.ModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExternalMcpToolPlannerLiveCaseTest {
    private ExternalMcpToolPlannerService plannerService;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Boolean.getBoolean("mcp.toolPlanner.live"),
            "Live MCP tool planner cases are disabled by default. Run with -Dmcp.toolPlanner.live=true to enable.");
        String apiKey = firstNonBlank(System.getProperty("model.apiKey"), System.getenv("MODEL_API_KEY"));
        assumeTrue(!apiKey.isBlank(),
            "Live MCP tool planner cases require -Dmodel.apiKey=... or MODEL_API_KEY.");

        ModelClient modelClient = new ModelClient();
        setField(modelClient, "apiKey", apiKey);
        plannerService = new ExternalMcpToolPlannerService(modelClient, new ObjectMapper());
    }

    @Test
    void shouldLiveChooseSearchWhenOnlyRepoKeywordProvided() {
        ExternalMcpAgentDecision decision = plannerService.decide(
            request("使用 GitHub 搜索 httpreading 的项目"),
            "Planner selected github because the user needs repository search.",
            githubReadTools(),
            List.of(),
            1,
            5);

        assertEquals("call_tool", decision.getStatus());
        assertNotNull(decision.getCall());
        assertEquals("search_repositories", decision.getCall().getToolName());
        assertNotEquals("get_repo", decision.getCall().getToolName());
        assertTrue(normalize(decision.getCall().getArguments().get("query")).contains("httpreading"));
    }

    @Test
    void shouldLiveReadReadmeWhenOwnerRepoProvided() {
        ExternalMcpAgentDecision decision = plannerService.decide(
            request("读取 LLhamster/reading_assistant 的 README.md"),
            "Planner selected github because the user asks to read a repository file.",
            githubReadTools(),
            List.of(),
            1,
            5);

        assertEquals("call_tool", decision.getStatus());
        assertNotNull(decision.getCall());
        assertNotEquals("search_repositories", decision.getCall().getToolName());
        assertTrue(List.of("fetch_file", "get_repo").contains(decision.getCall().getToolName()));
        if ("fetch_file".equals(decision.getCall().getToolName())) {
            assertEquals("LLhamster", decision.getCall().getArguments().get("owner"));
            assertEquals("reading_assistant", decision.getCall().getArguments().get("repo"));
            assertTrue(normalize(decision.getCall().getArguments().get("path")).contains("readme"));
        }
    }

    @Test
    void shouldLiveAvoidWriteTool() {
        ExternalMcpAgentDecision decision = plannerService.decide(
            request("帮我创建一个 README"),
            "Planner selected github, but this automatic MCP agent is read-only.",
            githubWriteAndSearchTools(),
            List.of(),
            1,
            5);

        if (decision.getCall() != null) {
            assertNotEquals("create_file", decision.getCall().getToolName());
        }
    }

    private AiChatRequest request(String question) {
        AiChatRequest request = new AiChatRequest();
        request.setQuestion(question);
        request.setBookId(1L);
        request.setChapterIndex(1);
        return request;
    }

    private List<Map<String, Object>> githubReadTools() {
        return List.of(
            descriptor("search_repositories", "Search GitHub repositories by keyword.", List.of("query")),
            descriptor("fetch_file", "Fetch a file from a GitHub repository by owner, repo, path.",
                List.of("owner", "repo", "path")),
            descriptor("get_repo", "Get repository metadata by owner and repo.", List.of("owner", "repo")));
    }

    private List<Map<String, Object>> githubWriteAndSearchTools() {
        return List.of(
            descriptor("create_file", "Create or update a file.", List.of("owner", "repo", "path", "content")),
            descriptor("search_repositories", "Search GitHub repositories by keyword.", List.of("query")));
    }

    private Map<String, Object> descriptor(String toolName, String description, List<String> required) {
        return Map.of(
            "serverName", "github",
            "toolName", toolName,
            "description", description,
            "inputSchema", Map.of(
                "type", "object",
                "required", required));
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
