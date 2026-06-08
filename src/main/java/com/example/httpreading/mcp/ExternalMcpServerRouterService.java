package com.example.httpreading.mcp;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.service.ModelClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ExternalMcpServerRouterService {
    private static final Logger log = LoggerFactory.getLogger(ExternalMcpServerRouterService.class);
    private final ModelClient modelClient;
    private final ObjectMapper objectMapper;

    public ExternalMcpServerRouterService(ModelClient modelClient, ObjectMapper objectMapper) {
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
    }

    public ExternalMcpServerRoute route(AiChatRequest request,
                                        String planningContext,
                                        List<Map<String, Object>> servers) {
        if (servers == null || servers.isEmpty()) {
            return ExternalMcpServerRoute.skip("没有可用的外部 MCP server");
        }
        String raw;
        try {
            raw = modelClient.chat(buildPrompt(request, planningContext, servers));
        } catch (Exception exception) {
            log.warn("MCP server 路由模型调用异常，跳过外部 MCP: {}", exception.getMessage());
            return ExternalMcpServerRoute.skip("MCP server 路由模型调用异常，已跳过外部 MCP");
        }
        if (isModelFailure(raw)) {
            log.warn("MCP server 路由模型调用失败，跳过外部 MCP: {}", raw);
            return ExternalMcpServerRoute.skip("MCP server 路由模型调用失败，已跳过外部 MCP");
        }
        Map<String, Object> decision = parseJson(raw);
        if (decision.isEmpty()) {
            return ExternalMcpServerRoute.skip("MCP server 路由结果无法解析");
        }

        boolean useMcp = readBoolean(decision.get("useMcp"));
        String serverName = normalize(decision.get("serverName"));
        String reason = normalize(decision.get("reason"));
        if (!useMcp) {
            return ExternalMcpServerRoute.skip(reason.isBlank() ? "问题不需要外部 MCP 工具" : reason);
        }

        Set<String> allowedServers = servers.stream()
            .map(server -> normalize(server.get("name")))
            .filter(name -> !name.isBlank())
            .collect(Collectors.toSet());
        if (!allowedServers.contains(serverName)) {
            return ExternalMcpServerRoute.skip("路由选择的 MCP server 不存在或不可用: " + serverName);
        }
        return ExternalMcpServerRoute.use(serverName, reason);
    }

    private String buildPrompt(AiChatRequest request,
                               String planningContext,
                               List<Map<String, Object>> servers) {
        return """
            你是 MCP Server 路由器。你只判断当前用户问题是否需要调用某个外部 MCP server，不选择具体工具，不回答用户问题。
            你必须只返回严格 JSON，不要 Markdown，不要输出完整思维过程。

            路由原则：
            - 如果问题可以由本地阅读上下文、RAG、Memory 或普通模型知识回答，返回 useMcp=false。
            - 只有用户明确请求某个外部工具领域的信息，且该需求匹配某个 server 描述时，才返回 useMcp=true。
            - server 描述不匹配时禁止猜测，返回 useMcp=false。
            - GitHub server 只适合 GitHub 仓库、代码、commit、分支、README、文件内容等 GitHub 资料问题。
            - 通用百科、历史人物、阅读内容解释、普通概念解释，不应路由到 GitHub。

            用户问题：
            %s

            规划上下文：
            %s

            可用 MCP servers：
            %s

            返回格式：
            {"useMcp":true|false,"serverName":"server-name-or-empty","reason":"一句简短理由"}
            """.formatted(
            request == null ? "" : request.getQuestion(),
            planningContext == null ? "" : planningContext,
            toJson(servers));
    }

    private Map<String, Object> parseJson(String raw) {
        String json = extractJson(raw);
        if (json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private String extractJson(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        if (text.startsWith("{") && text.endsWith("}")) {
            return text;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return "";
    }

    private boolean readBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "true".equalsIgnoreCase(normalize(value));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return String.valueOf(value);
        }
    }

    private String normalize(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean isModelFailure(String raw) {
        String value = normalize(raw);
        return value.startsWith("模型接口请求失败")
            || value.startsWith("调用模型接口异常")
            || value.startsWith("模型返回格式不符合预期");
    }
}
