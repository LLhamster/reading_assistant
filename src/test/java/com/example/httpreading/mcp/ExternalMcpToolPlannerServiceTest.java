package com.example.httpreading.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void shouldParseCallToolDecision() {
        when(modelClient.chat(anyString())).thenReturn("""
            {
              "status": "call_tool",
              "assessment": "需要先搜索仓库",
              "reasoningSummary": "用户只给出仓库关键词，需要搜索仓库",
              "call": {
                "serverName": "github",
                "toolName": "search_repositories",
                "arguments": {
                  "query": "httpreading"
                }
              },
              "message": "",
              "options": []
            }
            """);

        ExternalMcpAgentDecision decision = plannerService.decide(
            request("使用 GitHub 搜索 httpreading 的项目"), "planning", githubTools(), List.of(), 1, 5);

        assertEquals("call_tool", decision.getStatus());
        assertNotNull(decision.getCall());
        assertEquals("github", decision.getCall().getServerName());
        assertEquals("search_repositories", decision.getCall().getToolName());
        assertEquals("httpreading", decision.getCall().getArguments().get("query"));
        assertTrue(!decision.getAssessment().isBlank());
        assertTrue(!decision.getReasoningSummary().isBlank());
    }

    @Test
    void shouldParseCompleteDecision() {
        when(modelClient.chat(anyString())).thenReturn("""
            {
              "status": "complete",
              "assessment": "已经获得目标仓库信息",
              "reasoningSummary": "搜索结果已经足够回答用户问题",
              "call": null,
              "message": "",
              "options": []
            }
            """);

        ExternalMcpAgentDecision decision = plannerService.decide(
            request("总结仓库信息"), "planning", githubTools(), List.of(), 2, 4);

        assertEquals("complete", decision.getStatus());
        assertNull(decision.getCall());
    }

    @Test
    void shouldParseNeedsConfirmationOptions() {
        when(modelClient.chat(anyString())).thenReturn("""
            {
              "status": "needs_confirmation",
              "assessment": "存在多个候选仓库",
              "reasoningSummary": "需要用户确认目标仓库",
              "call": null,
              "message": "搜索到多个可能仓库，请选择一个。",
              "options": [
                {
                  "id": "repo-1",
                  "label": "a/reading_assistant",
                  "description": "候选 A",
                  "value": {
                    "owner": "a",
                    "repo": "reading_assistant"
                  }
                },
                {
                  "id": "repo-2",
                  "label": "b/reading_assistant",
                  "description": "候选 B",
                  "value": {
                    "owner": "b",
                    "repo": "reading_assistant"
                  }
                }
              ]
            }
            """);

        ExternalMcpAgentDecision decision = plannerService.decide(
            request("读取 reading_assistant README"), "planning", githubTools(), List.of(), 2, 4);

        assertEquals("needs_confirmation", decision.getStatus());
        assertEquals(2, decision.getOptions().size());
        assertEquals("a/reading_assistant", decision.getOptions().get(0).getLabel());
        assertEquals("a", decision.getOptions().get(0).getValue().get("owner"));
    }

    @Test
    void shouldExtractJsonFromMarkdownFence() {
        when(modelClient.chat(anyString())).thenReturn("""
            ```json
            {
              "status": "call_tool",
              "assessment": "需要搜索",
              "reasoningSummary": "先搜索仓库",
              "call": {
                "serverName": "github",
                "toolName": "search_repositories",
                "arguments": {
                  "query": "httpreading"
                }
              },
              "message": "",
              "options": []
            }
            ```
            """);

        ExternalMcpAgentDecision decision = plannerService.decide(
            request("使用 GitHub 搜索 httpreading 的项目"), "planning", githubTools(), List.of(), 1, 5);

        assertEquals("call_tool", decision.getStatus());
        assertNotNull(decision.getCall());
        assertEquals("search_repositories", decision.getCall().getToolName());
    }

    @Test
    void shouldReturnFailedWhenModelOutputInvalidJson() {
        when(modelClient.chat(anyString())).thenReturn("我觉得应该先搜索仓库，但是这里不是 JSON。");

        ExternalMcpAgentDecision decision = plannerService.decide(
            request("使用 GitHub 搜索 httpreading 的项目"), "planning", githubTools(), List.of(), 1, 5);

        assertEquals("failed", decision.getStatus());
        assertTrue((decision.getAssessment() + decision.getMessage()).contains("AUTO_PLAN_PARSE_FAILED"));
    }

    @Test
    void shouldIncludeAllowedToolsAndObservationsInPrompt() {
        when(modelClient.chat(anyString())).thenReturn(
            "{\"status\":\"complete\",\"assessment\":\"done\",\"reasoningSummary\":\"done\",\"call\":null}");
        ExternalMcpAgentObservation observation = new ExternalMcpAgentObservation(
            1,
            call("search_repositories", Map.of("query", "httpreading")),
            ExternalMcpCallResult.success("github", "search_repositories",
                "{\"items\":[{\"full_name\":\"LLhamster/reading_assistant\"}]}"));

        plannerService.decide(
            request("使用 GitHub 搜索 httpreading 的项目"),
            "Planner selected github because external repository search is required.",
            githubReadTools(),
            List.of(observation),
            2,
            4);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(modelClient).chat(prompt.capture());
        String value = prompt.getValue();
        assertTrue(value.contains("allowedTools"));
        assertTrue(value.contains("search_repositories"));
        assertTrue(value.contains("fetch_file"));
        assertTrue(value.contains("get_repo"));
        assertTrue(value.contains("observations"));
        assertTrue(value.contains("LLhamster/reading_assistant"));
        assertTrue(value.contains("当前轮次：2"));
        assertTrue(value.contains("剩余工具调用额度：4"));
        assertTrue(value.contains("使用 GitHub 搜索 httpreading 的项目"));
    }

    @Test
    void shouldIncludeSelfLocalProfileToolDescriptionsInPrompt() {
        when(modelClient.chat(anyString())).thenReturn(
            "{\"status\":\"complete\",\"assessment\":\"done\",\"reasoningSummary\":\"done\",\"call\":null}");

        plannerService.decide(
            request("这可以和我以前学过的什么联系起来？"),
            "Planner selected self-local because user profile and knowledge state may help.",
            selfLocalProfileTools(),
            List.of(),
            1,
            5);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(modelClient).chat(prompt.capture());
        String value = prompt.getValue();
        assertTrue(value.contains("profile_search_relevant"));
        assertTrue(value.contains("knowledge mastery state"));
        assertTrue(value.contains("next-reading recommendation"));
        assertTrue(value.contains("previous knowledge relation"));
        assertTrue(value.contains("standaloneQuestion"));
        assertTrue(value.contains("minScore"));
    }

    @Test
    void shouldIncludeWebSearchGuidanceInPrompt() {
        when(modelClient.chat(anyString())).thenReturn(
            "{\"status\":\"complete\",\"assessment\":\"done\",\"reasoningSummary\":\"done\",\"call\":null}");

        plannerService.decide(
            request("搜索一下今天的 AI 新闻"),
            "Planner selected web-search because current web information is required.",
            webSearchTools(),
            List.of(),
            1,
            5);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(modelClient).chat(prompt.capture());
        String value = prompt.getValue();
        assertTrue(value.contains("web_search"));
        assertTrue(value.contains("web_fetch"));
        assertTrue(value.contains("优先用 web_search"));
        assertTrue(value.contains("再调用 web_fetch"));
    }


    @Test
    void shouldLimitOptionsToThree() {
        when(modelClient.chat(anyString())).thenReturn("""
            {
              "status": "needs_confirmation",
              "assessment": "存在多个候选仓库",
              "reasoningSummary": "需要用户确认",
              "call": null,
              "message": "请选择仓库",
              "options": [
                {"id":"repo-1","label":"a/reading_assistant","description":"A","value":{"owner":"a","repo":"reading_assistant"}},
                {"id":"repo-2","label":"b/reading_assistant","description":"B","value":{"owner":"b","repo":"reading_assistant"}},
                {"id":"repo-3","label":"c/reading_assistant","description":"C","value":{"owner":"c","repo":"reading_assistant"}},
                {"id":"repo-4","label":"d/reading_assistant","description":"D","value":{"owner":"d","repo":"reading_assistant"}},
                {"id":"repo-5","label":"e/reading_assistant","description":"E","value":{"owner":"e","repo":"reading_assistant"}}
              ]
            }
            """);

        ExternalMcpAgentDecision decision = plannerService.decide(
            request("读取 reading_assistant README"), "planning", githubTools(), List.of(), 1, 5);

        assertEquals("needs_confirmation", decision.getStatus());
        assertEquals(3, decision.getOptions().size());
    }

    @Test
    void longToolObservationIsTruncatedBeforeNextPlanningCall() {
        when(modelClient.chat(anyString())).thenReturn(
            "{\"status\":\"complete\",\"assessment\":\"done\",\"call\":null}");
        String largeResult = "x".repeat(12000);
        ExternalMcpAgentObservation observation = new ExternalMcpAgentObservation(
            1,
            call("fetch_file", Map.of("owner", "LLhamster", "repo", "httpreader", "path", "README.md")),
            ExternalMcpCallResult.success("github", "fetch_file", largeResult));

        plannerService.decide(request("读取 LLhamster/httpreader README"), "planning", githubTools(), List.of(observation), 2, 4);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(modelClient).chat(prompt.capture());
        assertTrue(prompt.getValue().contains("[tool result truncated]"));
        assertTrue(prompt.getValue().length() < 10000);
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

    private List<Map<String, Object>> githubTools() {
        return List.of(
            descriptor("search_repositories", "Search GitHub repositories by keyword.", List.of("query")),
            descriptor("fetch_file", "Fetch a file from a GitHub repository by owner, repo, path.",
                List.of("owner", "repo", "path")),
            descriptor("get_repo", "Get repository metadata by owner and repo.", List.of("owner", "repo")),
            descriptor("create_file", "Create or update a file.", List.of("owner", "repo", "path", "content")));
    }

    private List<Map<String, Object>> selfLocalProfileTools() {
        return List.of(Map.of(
            "serverName", "self-local",
            "toolName", "profile_search_relevant",
            "description", "Search user profile snippets for style, reading understanding state, knowledge mastery state, next-reading recommendation, and previous knowledge relation.",
            "inputSchema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "standaloneQuestion", Map.of("type", "string"),
                    "minScore", Map.of("type", "number")))));
    }

    private List<Map<String, Object>> webSearchTools() {
        return List.of(
            Map.of(
                "serverName", "web-search",
                "toolName", "web_search",
                "description", "Search public web pages and return title, url, snippet, publishedAt, source.",
                "inputSchema", Map.of("type", "object", "required", List.of("query"))),
            Map.of(
                "serverName", "web-search",
                "toolName", "web_fetch",
                "description", "Fetch a web page URL and return title, url, summary or content, publishedAt, source.",
                "inputSchema", Map.of("type", "object", "required", List.of("url"))));
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

    private ExternalMcpCall call(String toolName, Map<String, Object> arguments) {
        ExternalMcpCall call = new ExternalMcpCall();
        call.setServerName("github");
        call.setToolName(toolName);
        call.setArguments(arguments);
        return call;
    }
}
