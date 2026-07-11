package com.example.httpreading.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.mcp.ExternalMcpClientService;
import com.example.httpreading.service.ModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlannerServiceTest {
    private ModelClient modelClient;
    private ExternalMcpClientService externalMcpClientService;
    private PlannerService plannerService;

    @BeforeEach
    void setUp() {
        ToolRegistry toolRegistry = new ToolRegistry();
        modelClient = mock(ModelClient.class);
        externalMcpClientService = mock(ExternalMcpClientService.class);
        when(externalMcpClientService.routableServers()).thenReturn(List.of(selfLocalServer()));
        plannerService = new PlannerService(
            modelClient,
            new ObjectMapper(),
            new PlannerPromptBuilder(externalMcpClientService),
            new PlanValidator(toolRegistry, externalMcpClientService),
            externalMcpClientService);
    }

    @Test
    void smallTalkUsesNoToolFromLlmPlan() {
        when(modelClient.chat(anyString())).thenReturn(planJson(
            "SMALL_TALK",
            "NONE",
            "你好",
            false,
            "NO_TOOL",
            "[]",
            "[]",
            0));

        ChatPlan plan = plannerService.plan(request("你好"));

        assertEquals(ToolExecutionMode.NO_TOOL, plan.executionMode());
        assertTrue(plan.toolPlan().isEmpty());
    }

    @Test
    void selectedTextQuestionCanUseCurrentPageTool() {
        when(modelClient.chat(anyString())).thenReturn(planJson(
            "READING_QA",
            "NONE",
            "解释划词内容是什么意思",
            true,
            "BOUNDED_REACT",
            "[\"mcp.server:self-local\"]",
            "[]",
            5,
            "CONTEXT_ANCHORED_MODEL_KNOWLEDGE"));
        AiChatRequest request = request("这里是什么意思？");
        request.setSelectedText("差序格局");
        request.setSelectedContext("上下文");

        ChatPlan plan = plannerService.plan(request);

        assertEquals(ToolExecutionMode.BOUNDED_REACT, plan.executionMode());
        assertEquals(List.of("mcp.server:self-local"), plan.allowedTools());
        assertTrue(plan.toolPlan().isEmpty());
    }

    @Test
    void bookEvidenceQuestionCanUseRagSearch() {
        when(modelClient.chat(anyString())).thenReturn(planJson(
            "READING_QA",
            "NONE",
            "书里是怎么解释差序格局的？",
            true,
            "BOUNDED_REACT",
            "[\"mcp.server:self-local\"]",
            "[]",
            5,
            "CONTEXT_ANCHORED_MODEL_KNOWLEDGE"));

        ChatPlan plan = plannerService.plan(request("书里是怎么解释差序格局的？"));

        assertEquals(ToolExecutionMode.BOUNDED_REACT, plan.executionMode());
        assertEquals(List.of("mcp.server:self-local"), plan.allowedTools());
        assertTrue(plan.toolPlan().isEmpty());
    }

    @Test
    void memoryQuestionCanUseMemorySearch() {
        when(modelClient.chat(anyString())).thenReturn(planJson(
            "MEMORY_QA",
            "NONE",
            "结合用户之前的理解解释当前问题",
            true,
            "BOUNDED_REACT",
            "[\"mcp.server:self-local\"]",
            "[]",
            5,
            "CONTEXT_ANCHORED_MODEL_KNOWLEDGE"));

        ChatPlan plan = plannerService.plan(request("结合我之前的理解说一下。"));

        assertEquals(ToolExecutionMode.BOUNDED_REACT, plan.executionMode());
        assertEquals(List.of("mcp.server:self-local"), plan.allowedTools());
        assertTrue(plan.toolPlan().isEmpty());
    }

    @Test
    void multiToolQuestionCanUseMemoryAndRag() {
        when(modelClient.chat(anyString())).thenReturn(planJson(
            "READING_QA",
            "NONE",
            "结合用户之前的问题和书里内容解释当前问题",
            true,
            "BOUNDED_REACT",
            "[\"mcp.server:self-local\"]",
            "[]",
            5,
            "CONTEXT_ANCHORED_MODEL_KNOWLEDGE"));

        ChatPlan plan = plannerService.plan(request("结合我之前的问题和书里的内容解释一下。"));

        assertEquals(ToolExecutionMode.BOUNDED_REACT, plan.executionMode());
        assertEquals(List.of("mcp.server:self-local"), plan.allowedTools());
        assertTrue(plan.toolPlan().isEmpty());
    }

    @Test
    void illegalToolFallsBackToReadingPlan() {
        when(modelClient.chat(anyString())).thenReturn(planJson(
            "READING_QA",
            "NONE",
            "危险工具测试",
            true,
            "SINGLE_TOOL",
            "[\"system.delete_all\"]",
            "[{\"toolName\":\"system.delete_all\",\"arguments\":{},\"reason\":\"错误工具\"}]",
            1));

        ChatPlan plan = plannerService.plan(request("解释一下"));

        assertFallback(plan);
    }

    @Test
    void externalMcpToolFallsBackWhenNotEnabled() {
        when(modelClient.chat(anyString())).thenReturn(planJson(
            "READING_QA",
            "NONE",
            "外部工具测试",
            true,
            "SINGLE_TOOL",
            "[\"external.github.search_code\"]",
            "[{\"toolName\":\"external.github.search_code\",\"arguments\":{},\"reason\":\"外部工具\"}]",
            1));

        ChatPlan plan = plannerService.plan(request("分析代码"));

        assertFallback(plan);
    }

    @Test
    void noToolWithToolPlanFallsBack() {
        when(modelClient.chat(anyString())).thenReturn(planJson(
            "READING_QA",
            "NONE",
            "模式冲突",
            true,
            "NO_TOOL",
            "[\"rag.search\"]",
            "[{\"toolName\":\"rag.search\",\"arguments\":{\"bookId\":1,\"chapterIndex\":1,\"query\":\"q\",\"topK\":5},\"reason\":\"冲突\"}]",
            1));

        ChatPlan plan = plannerService.plan(request("解释一下"));

        assertFallback(plan);
    }

    @Test
    void maxStepsOverLimitFallsBack() {
        when(modelClient.chat(anyString())).thenReturn(planJson(
            "READING_QA",
            "NONE",
            "步数过大",
            true,
            "SINGLE_TOOL",
            "[\"rag.search\"]",
            "[{\"toolName\":\"rag.search\",\"arguments\":{\"bookId\":1,\"chapterIndex\":1,\"query\":\"q\",\"topK\":5},\"reason\":\"检索\"}]",
            6));

        ChatPlan plan = plannerService.plan(request("解释一下"));

        assertFallback(plan);
    }

    @Test
    void invalidJsonFallsBack() {
        when(modelClient.chat(anyString())).thenReturn("不是 JSON");

        ChatPlan plan = plannerService.plan(request("解释一下"));

        assertFallback(plan);
    }

    @Test
    void githubSearchWithoutGithubToolUsesUnsupportedExternalPlan() {
        when(modelClient.chat(anyString())).thenReturn(planJson(
            "READING_QA",
            "NONE",
            "使用 GitHub 搜索 httpreading 的项目",
            true,
            "MULTI_TOOL",
            "[\"memory.search\",\"rag.search\"]",
            "[{\"toolName\":\"memory.search\",\"arguments\":{\"userId\":\"default_user\",\"sessionId\":\"book_1_chapter_1\",\"query\":\"httpreading github\",\"limit\":5},\"reason\":\"错误地用记忆替代 GitHub 搜索\"},{\"toolName\":\"rag.search\",\"arguments\":{\"bookId\":1,\"chapterIndex\":1,\"query\":\"httpreading github\",\"topK\":5},\"reason\":\"错误地用 RAG 替代 GitHub 搜索\"}]",
            2));

        ChatPlan plan = plannerService.plan(request("使用github搜索httpreading的项目"));

        assertEquals(ToolExecutionMode.NO_TOOL, plan.executionMode());
        assertEquals(AnswerMode.EXTERNAL_SEARCH_REQUIRED, plan.answerMode());
        assertEquals(EvidenceStrictness.STRICT, plan.evidenceStrictness());
        assertTrue(plan.toolPlan().isEmpty());
        assertTrue(plan.allowedTools().isEmpty());
        assertTrue(plan.answerGuidance().contains("没有可用的 GitHub/网页/外部搜索 MCP 工具"));
    }

    @Test
    void githubSearchWithOnlySelfLocalServerStillUsesUnsupportedExternalPlan() {
        when(modelClient.chat(anyString())).thenReturn(planJson(
            "READING_QA",
            "NONE",
            "使用 GitHub 搜索 httpreading 的项目",
            true,
            "BOUNDED_REACT",
            "[\"mcp.server:self-local\"]",
            "[]",
            5,
            "EXTERNAL_SEARCH_REQUIRED"));
        AiChatRequest request = request("使用github搜索httpreading的项目");
        request.setEnableExternalMcp(true);

        ChatPlan plan = plannerService.plan(request);

        assertEquals(ToolExecutionMode.NO_TOOL, plan.executionMode());
        assertEquals(AnswerMode.EXTERNAL_SEARCH_REQUIRED, plan.answerMode());
        assertTrue(plan.allowedTools().isEmpty());
        assertTrue(plan.toolPlan().isEmpty());
        assertTrue(plan.answerGuidance().contains("没有可用的 GitHub/网页/外部搜索 MCP 工具"));
    }

    @Test
    void webSearchWithWebSearchServerUsesBoundedReactServerPlan() {
        when(externalMcpClientService.routableServers()).thenReturn(List.of(
            selfLocalServer(),
            Map.of(
                "name", "web-search",
                "description", "通用网页搜索、新闻、最新情况和事实核验",
                "allowedTools", List.of("web_search", "web_fetch"))));
        when(modelClient.chat(anyString())).thenReturn(planJson(
            "TOOL_ACTION",
            "NONE",
            "搜索一下今天的 AI 新闻",
            false,
            "BOUNDED_REACT",
            "[\"mcp.server:web-search\"]",
            "[]",
            5,
            "EXTERNAL_SEARCH_REQUIRED"));
        AiChatRequest request = request("搜索一下今天的 AI 新闻");
        request.setEnableExternalMcp(true);

        ChatPlan plan = plannerService.plan(request);

        assertEquals(ToolExecutionMode.BOUNDED_REACT, plan.executionMode());
        assertEquals(List.of("mcp.server:web-search"), plan.allowedTools());
        assertTrue(plan.toolPlan().isEmpty());
        assertEquals(AnswerMode.EXTERNAL_SEARCH_REQUIRED, plan.answerMode());
    }

    @Test
    void webSearchWithOnlyGithubServerUsesUnsupportedExternalPlan() {
        when(externalMcpClientService.routableServers()).thenReturn(List.of(Map.of(
            "name", "github",
            "description", "GitHub 仓库资料",
            "allowedTools", List.of("search_repositories"))));
        when(modelClient.chat(anyString())).thenReturn("不是 JSON");
        AiChatRequest request = request("搜索一下今天的 AI 新闻");
        request.setEnableExternalMcp(true);

        ChatPlan plan = plannerService.plan(request);

        assertEquals(ToolExecutionMode.NO_TOOL, plan.executionMode());
        assertEquals(AnswerMode.EXTERNAL_SEARCH_REQUIRED, plan.answerMode());
        assertTrue(plan.allowedTools().isEmpty());
        assertTrue(plan.answerGuidance().contains("没有可用的 GitHub/网页/外部搜索 MCP 工具"));
    }

    @Test
    void githubSearchWithGithubServerUsesBoundedReactServerPlan() {
        when(externalMcpClientService.routableServers()).thenReturn(List.of(Map.of(
            "name", "github",
            "description", "GitHub 仓库资料",
            "allowedTools", List.of("search_repositories", "get_file_contents"))));
        when(modelClient.chat(anyString())).thenReturn(planJson(
            "TOOL_ACTION",
            "NONE",
            "使用 GitHub 搜索 httpreading 的项目",
            true,
            "BOUNDED_REACT",
            "[\"mcp.server:github\"]",
            "[]",
            5,
            "EXTERNAL_SEARCH_REQUIRED"));
        AiChatRequest request = request("使用github搜索httpreading的项目");
        request.setEnableExternalMcp(true);

        ChatPlan plan = plannerService.plan(request);

        assertEquals(ToolExecutionMode.BOUNDED_REACT, plan.executionMode());
        assertEquals(List.of("mcp.server:github"), plan.allowedTools());
        assertTrue(plan.toolPlan().isEmpty());
    }

    private void assertFallback(ChatPlan plan) {
        assertEquals(PlannerTaskType.READING_QA, plan.taskType());
        assertTrue(plan.planningReason().contains("fallback") || plan.planningReason().contains("兜底"));
        assertTrue(plan.toolPlan().stream().anyMatch(step -> "context.get_recent_dialogue".equals(step.toolName())));
        assertTrue(plan.toolPlan().stream().anyMatch(step -> "memory.search".equals(step.toolName())));
        assertTrue(plan.toolPlan().stream().anyMatch(step -> "rag.search".equals(step.toolName())));
    }

    private String planJson(String taskType,
                            String subIntent,
                            String standaloneQuestion,
                            boolean dependsOnContext,
                            String executionMode,
                            String allowedTools,
                            String toolPlan,
                            int maxSteps) {
        return """
            {
              "taskType": "%s",
              "subIntent": "%s",
              "standaloneQuestion": "%s",
              "dependsOnContext": %s,
              "executionMode": "%s",
              "allowedTools": %s,
              "toolPlan": %s,
              "taskGoal": "完成回答规划",
              "maxSteps": %d,
              "stopCondition": "完成 toolPlan 后停止",
              "answerGuidance": "依据证据回答，不要模板化。",
              "answerMode": "TEXT_ONLY",
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
              "planningReason": "模型规划"
            }
            """.formatted(taskType, subIntent, standaloneQuestion, dependsOnContext, executionMode,
            allowedTools, toolPlan, maxSteps);
    }

    private String planJson(String taskType,
                            String subIntent,
                            String standaloneQuestion,
                            boolean dependsOnContext,
                            String executionMode,
                            String allowedTools,
                            String toolPlan,
                            int maxSteps,
                            String answerMode) {
        return planJson(taskType, subIntent, standaloneQuestion, dependsOnContext, executionMode, allowedTools,
            toolPlan, maxSteps).replace("\"answerMode\": \"TEXT_ONLY\"", "\"answerMode\": \"" + answerMode + "\"");
    }

    private AiChatRequest request(String question) {
        AiChatRequest request = new AiChatRequest();
        request.setBookId(1L);
        request.setChapterIndex(1);
        request.setQuestion(question);
        request.setEnableMemory(true);
        request.setEnableRag(true);
        return request;
    }

    private Map<String, Object> selfLocalServer() {
        return Map.of(
            "name", "self-local",
            "description", "本项目内部阅读系统能力",
            "allowedTools", List.of(
                "memory_search",
                "rag_retrieve",
                "context_get_recent_dialogue",
                "context_get_current_page",
                "context_build"));
    }
}
