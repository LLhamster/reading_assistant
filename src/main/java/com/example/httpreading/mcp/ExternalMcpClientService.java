package com.example.httpreading.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.example.httpreading.dto.ExternalMcpCall;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

@Service
public class ExternalMcpClientService {
    private final ExternalMcpClientProperties properties;
    private final ExternalMcpClientFactory clientFactory;
    private final ObjectMapper objectMapper;
    private final Map<String, ExternalMcpClientSession> clients = new ConcurrentHashMap<>();

    public ExternalMcpClientService(ExternalMcpClientProperties properties,
                                    ExternalMcpClientFactory clientFactory,
                                    ObjectMapper objectMapper) {
        this.properties = properties;
        this.clientFactory = clientFactory;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> configuredServers() {
        return properties.getServers().stream()
            .map(server -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("name", server.getName());
                data.put("description", server.getDescription());
                data.put("url", server.getUrl());
                data.put("enabled", server.isEnabled());
                data.put("timeoutSeconds", server.getTimeoutSeconds());
                data.put("headerNames", new ArrayList<>(server.getHeaders().keySet()));
                data.put("allowedTools", new ArrayList<>(server.getAllowedTools()));
                return data;
            })
            .toList();
    }

    public List<Map<String, Object>> routableServers() {
        return properties.getServers().stream()
            .filter(server -> server.isEnabled())
            .filter(server -> server.getUrl() != null && !server.getUrl().isBlank())
            .filter(server -> !server.getAllowedTools().isEmpty())
            .map(server -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("name", server.getName());
                data.put("description", server.getDescription());
                data.put("allowedTools", new ArrayList<>(server.getAllowedTools()));
                return data;
            })
            .toList();
    }

    public List<Map<String, Object>> allowedToolDescriptors() {
        List<Map<String, Object>> descriptors = new ArrayList<>();
        for (ExternalMcpClientProperties.Server server : properties.getServers()) {
            if (!server.isEnabled() || server.getUrl() == null || server.getUrl().isBlank()
                || server.getAllowedTools().isEmpty()) {
                continue;
            }
            Map<String, Map<String, Object>> metadataByName = toolMetadata(server);
            for (String toolName : server.getAllowedTools()) {
                String normalizedToolName = normalize(toolName);
                if (normalizedToolName.isBlank()) {
                    continue;
                }
                Map<String, Object> descriptor = new LinkedHashMap<>();
                descriptor.put("serverName", server.getName());
                descriptor.put("toolName", normalizedToolName);
                Map<String, Object> metadata = metadataByName.get(normalizedToolName);
                if (metadata != null) {
                    descriptor.put("description", metadata.get("description"));
                    descriptor.put("inputSchema", metadata.get("inputSchema"));
                }
                descriptors.add(descriptor);
            }
        }
        return descriptors;
    }

    public List<Map<String, Object>> allowedToolDescriptors(String serverName) {
        String normalizedServerName = normalize(serverName);
        return properties.getServers().stream()
            .filter(server -> server.isEnabled())
            .filter(server -> normalizedServerName.equals(server.getName()))
            .filter(server -> server.getUrl() != null && !server.getUrl().isBlank())
            .findFirst()
            .map(this::allowedToolDescriptors)
            .orElse(List.of());
    }

    private List<Map<String, Object>> allowedToolDescriptors(ExternalMcpClientProperties.Server server) {
        if (server.getAllowedTools().isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, Object>> metadataByName = toolMetadata(server);
        List<Map<String, Object>> descriptors = new ArrayList<>();
        for (String toolName : server.getAllowedTools()) {
            String normalizedToolName = normalize(toolName);
            if (normalizedToolName.isBlank()) {
                continue;
            }
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("serverName", server.getName());
            descriptor.put("toolName", normalizedToolName);
            Map<String, Object> metadata = metadataByName.get(normalizedToolName);
            if (metadata != null) {
                descriptor.put("description", metadata.get("description"));
                descriptor.put("inputSchema", metadata.get("inputSchema"));
            }
            descriptors.add(descriptor);
        }
        return descriptors;
    }

    public List<Map<String, Object>> listTools(String serverName) {
        ExternalMcpClientProperties.Server server = findEnabledServer(serverName)
            .orElseThrow(() -> new IllegalArgumentException("外部 MCP server 不存在或未启用: " + serverName));
        ExternalMcpClientSession client = client(server);
        return client.listTools().tools().stream()
            .map(this::toolToMap)
            .toList();
    }

    public List<ExternalMcpCallResult> callTools(List<ExternalMcpCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        return calls.stream().map(this::callTool).collect(Collectors.toList());
    }

    public boolean isToolAllowed(String serverName, String toolName) {
        String normalizedServer = normalize(serverName);
        String normalizedTool = normalize(toolName);
        return findEnabledServer(normalizedServer)
            .map(server -> server.getAllowedTools().stream()
                .map(this::normalize)
                .anyMatch(normalizedTool::equals))
            .orElse(false);
    }

    public ExternalMcpCallResult callTool(ExternalMcpCall call) {
        String serverName = call == null ? "" : normalize(call.getServerName());
        String toolName = call == null ? "" : normalize(call.getToolName());
        if (serverName.isBlank()) {
            return ExternalMcpCallResult.failure("unknown", toolName, "serverName 不能为空");
        }
        if (toolName.isBlank()) {
            return ExternalMcpCallResult.failure(serverName, "unknown", "toolName 不能为空");
        }

        Optional<ExternalMcpClientProperties.Server> server = findEnabledServer(serverName);
        if (server.isEmpty()) {
            return ExternalMcpCallResult.failure(serverName, toolName, "外部 MCP server 不存在或未启用");
        }

        try {
            CallToolRequest request = new CallToolRequest(toolName, call.getArguments());
            CallToolResult result = client(server.get()).callTool(request);
            String content = contentText(result);
            if (Boolean.TRUE.equals(result.isError())) {
                return ExternalMcpCallResult.failure(serverName, toolName, content.isBlank() ? "工具返回错误" : content);
            }
            return ExternalMcpCallResult.success(serverName, toolName, content);
        } catch (Exception exception) {
            return ExternalMcpCallResult.failure(serverName, toolName, exception.getMessage());
        }
    }

    private ExternalMcpClientSession client(ExternalMcpClientProperties.Server server) {
        return clients.computeIfAbsent(server.getName(), ignored -> {
            ExternalMcpClientSession client = clientFactory.create(server);
            client.initialize();
            return client;
        });
    }

    private Optional<ExternalMcpClientProperties.Server> findEnabledServer(String serverName) {
        String normalized = normalize(serverName);
        return properties.getServers().stream()
            .filter(server -> server.isEnabled())
            .filter(server -> normalized.equals(server.getName()))
            .filter(server -> server.getUrl() != null && !server.getUrl().isBlank())
            .findFirst();
    }

    private Map<String, Object> toolToMap(Tool tool) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", tool.name());
        data.put("description", tool.description());
        data.put("inputSchema", tool.inputSchema());
        return data;
    }

    private Map<String, Map<String, Object>> toolMetadata(ExternalMcpClientProperties.Server server) {
        try {
            return client(server).listTools().tools().stream()
                .map(this::toolToMap)
                .collect(Collectors.toMap(
                    tool -> normalize((String) tool.get("name")),
                    tool -> tool,
                    (left, right) -> left,
                    LinkedHashMap::new));
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private String contentText(CallToolResult result) {
        if (result == null || result.content() == null || result.content().isEmpty()) {
            return "";
        }
        return result.content().stream()
            .map(this::contentToString)
            .filter(text -> text != null && !text.isBlank())
            .collect(Collectors.joining("\n"));
    }

    private String contentToString(Content content) {
        if (content instanceof TextContent textContent) {
            return textContent.text();
        }
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException exception) {
            return String.valueOf(content);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    @PreDestroy
    public void closeClients() {
        clients.values().forEach(client -> {
            try {
                client.close();
            } catch (Exception ignored) {
                // best effort close
            }
        });
        clients.clear();
    }
}
