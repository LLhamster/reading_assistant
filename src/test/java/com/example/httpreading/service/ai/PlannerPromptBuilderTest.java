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
        PlannerPromptBuilder builder = new PlannerPromptBuilder(externalMcpClientService);

        String prompt = builder.build(request());

        assertTrue(prompt.contains("可调用 MCP server 白名单"));
        assertTrue(prompt.contains("mcp.server:self-local"));
        assertTrue(prompt.contains("memory_search"));
        assertTrue(prompt.contains("rag_retrieve"));
        assertTrue(prompt.contains("\"answerRequirement\""));
        assertTrue(prompt.contains("不能输出 Markdown"));
        assertTrue(prompt.contains("maxSteps<=5"));
        assertTrue(prompt.contains("toolPlan=[]"));
        assertTrue(prompt.contains("一级 Planner 永远不输出 server 内部具体工具名"));
        assertTrue(prompt.contains("minDetailLevel 只能是 LOW、MEDIUM、HIGH"));
        assertTrue(prompt.contains("\"taskTypeReason\""));
        assertTrue(prompt.contains("taskTypeReason 必须说明为什么选择该 taskType"));
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
        PlannerPromptBuilder builder = new PlannerPromptBuilder(externalMcpClientService);
        AiChatRequest request = request();
        request.setEnableExternalMcp(true);

        String prompt = builder.build(request);

        assertTrue(prompt.contains("可调用 MCP server 白名单"));
        assertTrue(prompt.contains("mcp.server:self-local"));
        assertTrue(prompt.contains("mcp.server:github"));
        assertTrue(prompt.contains("GitHub 仓库资料"));
        assertTrue(prompt.contains("allowedTools 只能为空，或只包含一个白名单中的 mcp.server:*"));
        assertTrue(prompt.contains("如果没有匹配 server，不要用 self-local 凑数"));
        assertTrue(prompt.contains("外部搜索来补充资料，taskType=TOOL_ACTION"));
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
