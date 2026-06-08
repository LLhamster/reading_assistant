package com.example.httpreading.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.ServletRegistrationBean;

class McpServerConfigTest {

    @Test
    void createsStreamableHttpServerWithSevenTools() {
        McpServerConfig config = new McpServerConfig();
        McpJsonMapper jsonMapper = config.mcpJsonMapper(new ObjectMapper());
        HttpServletStreamableServerTransportProvider transportProvider =
            config.mcpTransportProvider(jsonMapper);
        ServletRegistrationBean<?> registration = config.mcpServlet(transportProvider);
        McpSyncServer server = config.mcpSyncServer(transportProvider, mock(ReadingMcpToolService.class));

        assertNotNull(transportProvider);
        assertEquals("mcpServlet", registration.getServletName());
        assertTrue(registration.getUrlMappings().contains("/mcp"));
        assertEquals(7, server.listTools().size());
        assertEquals("http-reading-mcp", server.getServerInfo().name());
    }
}
