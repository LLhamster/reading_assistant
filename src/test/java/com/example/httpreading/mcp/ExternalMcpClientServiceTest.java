package com.example.httpreading.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.example.httpreading.dto.ExternalMcpCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;

class ExternalMcpClientServiceTest {

    @Test
    void callToolReturnsFailureWhenServerMissing() {
        ExternalMcpClientService service = service(new ExternalMcpClientProperties(), server -> null);

        ExternalMcpCall call = new ExternalMcpCall();
        call.setServerName("missing");
        call.setToolName("search");

        ExternalMcpCallResult result = service.callTool(call);

        assertFalse(result.isOk());
        assertEquals("missing", result.getServerName());
        assertTrue(result.getError().contains("不存在"));
    }

    @Test
    void disabledServerIsNotCallable() {
        ExternalMcpClientProperties properties = properties(server("s1", false));
        ExternalMcpClientService service = service(properties, server -> null);

        ExternalMcpCall call = new ExternalMcpCall();
        call.setServerName("s1");
        call.setToolName("search");

        ExternalMcpCallResult result = service.callTool(call);

        assertFalse(result.isOk());
        assertTrue(result.getError().contains("未启用"));
    }

    @Test
    void callToolExtractsTextContentAndPassesServerConfigToFactory() {
        ExternalMcpClientProperties.Server configured = server("s1", true);
        configured.setHeaders(Map.of("Authorization", "Bearer token"));
        configured.setTimeoutSeconds(7);
        CapturingFactory factory = new CapturingFactory(new FakeSession(
            new CallToolResult(List.of(new TextContent("tool output")), false)));
        ExternalMcpClientService service = service(properties(configured), factory);

        ExternalMcpCall call = new ExternalMcpCall();
        call.setServerName("s1");
        call.setToolName("search");
        call.setArguments(Map.of("query", "hello"));

        ExternalMcpCallResult result = service.callTool(call);

        assertTrue(result.isOk());
        assertEquals("tool output", result.getContent());
        assertEquals("Bearer token", factory.server.getHeaders().get("Authorization"));
        assertEquals(7, factory.server.getTimeoutSeconds());
    }

    @Test
    void callToolExceptionReturnsFailure() {
        ExternalMcpClientService service = service(properties(server("s1", true)), server -> new ThrowingSession());

        ExternalMcpCall call = new ExternalMcpCall();
        call.setServerName("s1");
        call.setToolName("boom");

        ExternalMcpCallResult result = service.callTool(call);

        assertFalse(result.isOk());
        assertTrue(result.getError().contains("network down"));
    }

    @Test
    void listToolsReturnsToolMetadata() {
        FakeSession session = new FakeSession(new CallToolResult(List.of(new TextContent("ok")), false));
        session.tools = new ListToolsResult(List.of(Tool.builder()
            .name("search")
            .description("Search")
            .inputSchema(new JsonSchema("object", Map.of(), List.of(), false, null, null))
            .build()), null);
        ExternalMcpClientService service = service(properties(server("s1", true)), server -> session);

        List<Map<String, Object>> tools = service.listTools("s1");

        assertEquals(1, tools.size());
        assertEquals("search", tools.get(0).get("name"));
    }

    @Test
    void allowedToolDescriptorsReturnOnlyConfiguredAllowedTools() {
        ExternalMcpClientProperties.Server configured = server("s1", true);
        configured.setAllowedTools(List.of("search"));
        ExternalMcpClientProperties.Server disabled = server("disabled", false);
        disabled.setAllowedTools(List.of("hidden"));

        FakeSession session = new FakeSession(new CallToolResult(List.of(new TextContent("ok")), false));
        session.tools = new ListToolsResult(List.of(
            Tool.builder()
                .name("search")
                .description("Search")
                .inputSchema(new JsonSchema("object", Map.of(), List.of(), false, null, null))
                .build(),
            Tool.builder()
                .name("write")
                .description("Write")
                .inputSchema(new JsonSchema("object", Map.of(), List.of(), false, null, null))
                .build()), null);
        ExternalMcpClientService service = service(properties(configured, disabled), server -> session);

        List<Map<String, Object>> tools = service.allowedToolDescriptors();

        assertEquals(1, tools.size());
        assertEquals("s1", tools.get(0).get("serverName"));
        assertEquals("search", tools.get(0).get("toolName"));
        assertEquals("Search", tools.get(0).get("description"));
    }

    @Test
    void allowedToolDescriptorsExposeSelfLocalProfileDescriptions() {
        ExternalMcpClientProperties.Server configured = server("self-local", true);
        configured.setAllowedTools(List.of("profile_search_relevant"));

        FakeSession session = new FakeSession(new CallToolResult(List.of(new TextContent("ok")), false));
        session.tools = new ListToolsResult(List.of(Tool.builder()
            .name("profile_search_relevant")
            .description("Search user profile snippets for style, reading understanding state, knowledge mastery state, next-reading recommendation, and previous knowledge relation.")
            .inputSchema(new JsonSchema("object", Map.of(
                "standaloneQuestion", Map.of("type", "string"),
                "categoryCode", Map.of("type", "string"),
                "bookCategory", Map.of("type", "string"),
                "topK", Map.of("type", "integer"),
                "minScore", Map.of("type", "number")), List.of("userId"), false, null, null))
            .build()), null);
        ExternalMcpClientService service = service(properties(configured), server -> session);

        List<Map<String, Object>> tools = service.allowedToolDescriptors("self-local");

        assertEquals(1, tools.size());
        assertEquals("self-local", tools.get(0).get("serverName"));
        assertEquals("profile_search_relevant", tools.get(0).get("toolName"));
        assertTrue(String.valueOf(tools.get(0).get("description")).contains("knowledge mastery state"));
        assertTrue(String.valueOf(tools.get(0).get("inputSchema")).contains("standaloneQuestion"));
        assertTrue(String.valueOf(tools.get(0).get("inputSchema")).contains("minScore"));
    }

    @Test
    void isToolAllowedRechecksEnabledServerAndConfiguredWhitelist() {
        ExternalMcpClientProperties.Server configured = server("s1", true);
        configured.setAllowedTools(List.of("search"));
        ExternalMcpClientService service = service(properties(configured), server -> null);

        assertTrue(service.isToolAllowed("s1", "search"));
        assertFalse(service.isToolAllowed("s1", "write"));
        configured.setEnabled(false);
        assertFalse(service.isToolAllowed("s1", "search"));
    }

    private ExternalMcpClientService service(ExternalMcpClientProperties properties,
                                             ExternalMcpClientFactory factory) {
        return new ExternalMcpClientService(properties, factory, new ObjectMapper());
    }

    private ExternalMcpClientProperties properties(ExternalMcpClientProperties.Server... servers) {
        ExternalMcpClientProperties properties = new ExternalMcpClientProperties();
        properties.setServers(List.of(servers));
        return properties;
    }

    private ExternalMcpClientProperties.Server server(String name, boolean enabled) {
        ExternalMcpClientProperties.Server server = new ExternalMcpClientProperties.Server();
        server.setName(name);
        server.setUrl("http://localhost:8080/mcp");
        server.setEnabled(enabled);
        return server;
    }

    private static class CapturingFactory implements ExternalMcpClientFactory {
        private final ExternalMcpClientSession session;
        private ExternalMcpClientProperties.Server server;

        private CapturingFactory(ExternalMcpClientSession session) {
            this.session = session;
        }

        @Override
        public ExternalMcpClientSession create(ExternalMcpClientProperties.Server server) {
            this.server = server;
            return session;
        }
    }

    private static class FakeSession implements ExternalMcpClientSession {
        private final CallToolResult result;
        private ListToolsResult tools = new ListToolsResult(List.of(), null);

        private FakeSession(CallToolResult result) {
            this.result = result;
        }

        @Override
        public void initialize() {
        }

        @Override
        public ListToolsResult listTools() {
            return tools;
        }

        @Override
        public CallToolResult callTool(CallToolRequest request) {
            return result;
        }

        @Override
        public void close() {
        }
    }

    private static class ThrowingSession implements ExternalMcpClientSession {
        @Override
        public void initialize() {
        }

        @Override
        public ListToolsResult listTools() {
            return new ListToolsResult(List.of(), null);
        }

        @Override
        public CallToolResult callTool(CallToolRequest request) {
            throw new IllegalStateException("network down");
        }

        @Override
        public void close() {
        }
    }
}
