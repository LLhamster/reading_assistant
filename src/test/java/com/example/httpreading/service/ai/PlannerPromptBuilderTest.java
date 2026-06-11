package com.example.httpreading.service.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.mcp.ExternalMcpClientService;
import org.junit.jupiter.api.Test;

class PlannerPromptBuilderTest {
    @Test
    void promptContainsEnabledToolsSchemaAndRules() {
        ExternalMcpClientService externalMcpClientService = mock(ExternalMcpClientService.class);
        when(externalMcpClientService.routableServers()).thenReturn(List.of(Map.of(
            "name", "self-local",
            "description", "本项目内部阅读系统能力",
            "allowedTools", List.of("memory_search", "rag_retrieve", "context_get_recent_dialogue", "context_get_current_page"))));
        PlannerPromptBuilder builder = new PlannerPromptBuilder(new ToolRegistry(), externalMcpClientService);

        String prompt = builder.build(request());

        assertTrue(prompt.contains("可调用 MCP server 白名单"));
        assertTrue(prompt.contains("mcp.server:self-local"));
        assertTrue(prompt.contains("memory_search"));
        assertTrue(prompt.contains("rag_retrieve"));
        assertTrue(prompt.contains("\"answerRequirement\""));
        assertTrue(prompt.contains("不能输出 Markdown"));
        assertTrue(prompt.contains("maxSteps 不能超过 5"));
        assertTrue(prompt.contains("toolPlan 必须为空"));
        assertTrue(prompt.contains("不要输出 server 内部具体工具名"));
        assertFalse(prompt.contains("可用本地工具白名单"));
        assertFalse(prompt.contains("可用外部 MCP server 白名单"));
    }

    @Test
    void promptListsExternalMcpServerWhitelistWhenEnabled() {
        ExternalMcpClientService externalMcpClientService = mock(ExternalMcpClientService.class);
        when(externalMcpClientService.routableServers()).thenReturn(List.of(
            Map.of(
                "name", "self-local",
                "description", "本项目内部阅读系统能力",
                "allowedTools", List.of("memory_search", "rag_retrieve", "context_get_recent_dialogue")),
            Map.of(
                "name", "github",
                "description", "GitHub 仓库资料",
                "allowedTools", List.of("search_repositories", "get_file_contents"))));
        PlannerPromptBuilder builder = new PlannerPromptBuilder(new ToolRegistry(), externalMcpClientService);
        AiChatRequest request = request();
        request.setEnableExternalMcp(true);

        String prompt = builder.build(request);

        assertTrue(prompt.contains("可调用 MCP server 白名单"));
        assertTrue(prompt.contains("mcp.server:self-local"));
        assertTrue(prompt.contains("mcp.server:github"));
        assertTrue(prompt.contains("GitHub 仓库资料"));
        assertTrue(prompt.contains("一级 Planner 只选择 server，不选择具体工具"));
    }

    private AiChatRequest request() {
        AiChatRequest request = new AiChatRequest();
        request.setBookId(44L);
        request.setChapterIndex(1);
        request.setQuestion("这里是什么意思？");
        request.setSelectedText("差序格局");
        return request;
    }
}
