package com.example.httpreading.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.service.ModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExternalMcpServerRouterServiceTest {
    @Mock
    private ModelClient modelClient;

    private ExternalMcpServerRouterService routerService;

    @BeforeEach
    void setUp() {
        routerService = new ExternalMcpServerRouterService(modelClient, new ObjectMapper());
    }

    @Test
    void githubRepositoryQuestionRoutesToGithub() {
        when(modelClient.chat(anyString())).thenReturn(
            "{\"useMcp\":true,\"serverName\":\"github\",\"reason\":\"用户请求 GitHub 仓库 README\"}");

        ExternalMcpServerRoute route = routerService.route(
            request("查询 LLhamster/HTTP_READING 的 README"),
            "planning",
            List.of(githubServer()));

        assertTrue(route.isUseMcp());
        assertEquals("github", route.getServerName());
    }

    @Test
    void generalQuestionSkipsMcp() {
        when(modelClient.chat(anyString())).thenReturn(
            "{\"useMcp\":false,\"serverName\":\"\",\"reason\":\"普通百科问题\"}");

        ExternalMcpServerRoute route = routerService.route(
            request("毛泽东是谁"),
            "planning",
            List.of(githubServer()));

        assertFalse(route.isUseMcp());
        assertTrue(route.getReason().contains("普通百科"));
    }

    @Test
    void invalidJsonSkipsMcp() {
        when(modelClient.chat(anyString())).thenReturn("not json");

        ExternalMcpServerRoute route = routerService.route(
            request("查询 GitHub 仓库"),
            "planning",
            List.of(githubServer()));

        assertFalse(route.isUseMcp());
        assertTrue(route.getReason().contains("无法解析"));
    }

    @Test
    void modelFailureTextSkipsMcp() {
        when(modelClient.chat(anyString())).thenReturn("调用模型接口异常: timeout");

        ExternalMcpServerRoute route = routerService.route(
            request("查询 GitHub 仓库"),
            "planning",
            List.of(githubServer()));

        assertFalse(route.isUseMcp());
        assertTrue(route.getReason().contains("模型调用失败"));
    }

    @Test
    void modelExceptionSkipsMcp() {
        when(modelClient.chat(anyString())).thenThrow(new RuntimeException("timeout"));

        ExternalMcpServerRoute route = routerService.route(
            request("查询 GitHub 仓库"),
            "planning",
            List.of(githubServer()));

        assertFalse(route.isUseMcp());
        assertTrue(route.getReason().contains("模型调用异常"));
    }

    @Test
    void disabledServerIsNotIncludedWhenCallerPassesOnlyRoutableServers() {
        when(modelClient.chat(anyString())).thenReturn(
            "{\"useMcp\":false,\"serverName\":\"\",\"reason\":\"无匹配\"}");

        routerService.route(request("问题"), "planning", List.of(githubServer()));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(modelClient).chat(prompt.capture());
        assertTrue(prompt.getValue().contains("\"name\":\"github\""));
        assertFalse(prompt.getValue().contains("\"name\":\"disabled\""));
    }

    private AiChatRequest request(String question) {
        AiChatRequest request = new AiChatRequest();
        request.setBookId(1L);
        request.setChapterIndex(1);
        request.setQuestion(question);
        return request;
    }

    private Map<String, Object> githubServer() {
        return Map.of(
            "name", "github",
            "description", "用于查询 GitHub 仓库、代码、commit、README。",
            "allowedTools", List.of("search_repositories", "get_file_contents"));
    }
}
