package com.example.httpreading.service.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.mcp.ExternalMcpClientService;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class PlannerPromptBuilderTest {
    @Test
    void promptContainsEnabledToolsSchemaAndRules() {
        ExternalMcpClientService externalMcpClientService = mock(ExternalMcpClientService.class);
        when(externalMcpClientService.routableServers()).thenReturn(List.of(Map.of(
            "name", "self-local",
            "description", "本项目内部阅读系统能力，包含阅读上下文、RAG、记忆、用户画像、知识点掌握状态，可用于个性化解释、推荐下一步阅读、关联旧知识。",
            "allowedTools", List.of("memory_search", "rag_retrieve", "rag_answer",
                "context_get_recent_dialogue", "context_get_current_page",
                "profile_list_categories", "profile_get_category_detail", "profile_search_relevant"))));
        PlannerPromptBuilder builder = new PlannerPromptBuilder(externalMcpClientService);

        String prompt = builder.build(request());

        assertTrue(prompt.contains("可调用 MCP server 白名单"));
        assertTrue(prompt.contains("mcp.server:self-local"));
        assertTrue(prompt.contains("memory_search"));
        assertTrue(prompt.contains("rag_retrieve"));
        assertTrue(prompt.contains("profile_search_relevant"));
        assertTrue(prompt.contains("知识点掌握状态"));
        assertTrue(prompt.contains("推荐下一步阅读"));
        assertTrue(prompt.contains("关联旧知识"));
        assertTrue(prompt.contains("\"answerRequirement\""));
        assertTrue(prompt.contains("不能输出 Markdown"));
        assertTrue(prompt.contains("maxSteps<=5"));
        assertTrue(prompt.contains("toolPlan=[]"));
        assertTrue(prompt.contains("一级 Planner 永远不输出 server 内部具体工具名"));
        assertTrue(prompt.contains("不要在一级 Planner 中输出 server 内部具体工具名"));
        assertTrue(prompt.contains("minDetailLevel 只能是 LOW、MEDIUM、HIGH"));
        assertTrue(prompt.contains("\"taskTypeReason\""));
        assertTrue(prompt.contains("taskTypeReason 必须说明为什么选择该 taskType"));
        assertFalse(prompt.contains("用户画像工具规则"));
        assertFalse(prompt.contains("profile.search_relevant 必须传 standaloneQuestion"));
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

    @Test
    void selfLocalConfigIncludesProfileToolsInYamlAndExample() {
        List<String> required = List.of(
            "context_get_current_page",
            "context_get_recent_dialogue",
            "rag_retrieve",
            "rag_answer",
            "memory_search",
            "profile_list_categories",
            "profile_get_category_detail",
            "profile_search_relevant");

        Map<String, Object> app = selfLocalServer("application.yml");
        Map<String, Object> example = selfLocalServer("application.yml.example");

        assertDescriptionMentionsProfile(app);
        assertDescriptionMentionsProfile(example);
        assertTrue(allowedTools(app).containsAll(required));
        assertTrue(allowedTools(example).containsAll(required));
        assertEquals(allowedTools(example), allowedTools(app));
    }

    private AiChatRequest request() {
        AiChatRequest request = new AiChatRequest();
        request.setBookId(44L);
        request.setChapterIndex(1);
        request.setQuestion("这里是什么意思？");
        request.setSelectedText("差序格局");
        return request;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> selfLocalServer(String resourceName) {
        try (InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName)) {
            Map<String, Object> root = new Yaml().load(inputStream);
            Map<String, Object> mcp = (Map<String, Object>) root.get("mcp");
            Map<String, Object> client = (Map<String, Object>) mcp.get("client");
            List<Map<String, Object>> servers = (List<Map<String, Object>>) client.get("servers");
            return servers.stream()
                .filter(server -> "self-local".equals(server.get("name")))
                .findFirst()
                .orElseThrow();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to load " + resourceName, exception);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> allowedTools(Map<String, Object> server) {
        return (List<String>) server.get("allowedTools");
    }

    private void assertDescriptionMentionsProfile(Map<String, Object> server) {
        String description = String.valueOf(server.get("description"));
        assertTrue(description.contains("用户画像"));
        assertTrue(description.contains("个性化解释"));
        assertTrue(description.contains("推荐下一步阅读"));
        assertTrue(description.contains("关联"));
    }
}
