package com.example.httpreading.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.ServletRegistrationBean;

class McpServerConfigTest {

    @Test
    void createsStreamableHttpServerWithProfileTools() {
        McpServerConfig config = new McpServerConfig();
        McpJsonMapper jsonMapper = config.mcpJsonMapper(new ObjectMapper());
        HttpServletStreamableServerTransportProvider transportProvider =
            config.mcpTransportProvider(jsonMapper);
        ServletRegistrationBean<?> registration = config.mcpServlet(transportProvider);
        McpSyncServer server = config.mcpSyncServer(transportProvider, mock(ReadingMcpToolService.class));

        assertNotNull(transportProvider);
        assertEquals("mcpServlet", registration.getServletName());
        assertTrue(registration.getUrlMappings().contains("/mcp"));
        assertEquals(12, server.listTools().size());
        Map<String, Tool> tools = server.listTools().stream()
            .collect(java.util.stream.Collectors.toMap(Tool::name, tool -> tool));
        assertTrue(tools.containsKey("profile_list_categories"));
        assertTrue(tools.containsKey("profile_get_category_detail"));
        assertTrue(tools.containsKey("profile_search_relevant"));
        assertTrue(tools.containsKey("web_search"));
        assertTrue(tools.containsKey("web_fetch"));
        assertTrue(tools.get("profile_search_relevant").description().contains("knowledge mastery state"));
        assertTrue(tools.get("profile_search_relevant").description().contains("next-reading recommendation"));
        assertTrue(tools.get("profile_search_relevant").inputSchema().properties().containsKey("standaloneQuestion"));
        assertTrue(tools.get("profile_search_relevant").inputSchema().properties().containsKey("categoryCode"));
        assertTrue(tools.get("profile_search_relevant").inputSchema().properties().containsKey("bookCategory"));
        assertTrue(tools.get("profile_search_relevant").inputSchema().properties().containsKey("topK"));
        assertTrue(tools.get("profile_search_relevant").inputSchema().properties().containsKey("minScore"));
        assertEquals("http-reading-mcp", server.getServerInfo().name());
    }
}
