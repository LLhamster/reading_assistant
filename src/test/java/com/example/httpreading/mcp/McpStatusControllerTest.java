package com.example.httpreading.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;

import com.example.httpreading.api.CommonResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.junit.jupiter.api.Test;

class McpStatusControllerTest {

    @Test
    void statusReturnsRegisteredTools() {
        McpServerConfig config = new McpServerConfig();
        McpJsonMapper jsonMapper = config.mcpJsonMapper(new ObjectMapper());
        HttpServletStreamableServerTransportProvider transportProvider =
            config.mcpTransportProvider(jsonMapper);
        McpSyncServer server = config.mcpSyncServer(transportProvider, mock(ReadingMcpToolService.class));

        CommonResponse<Map<String, Object>> response = new McpStatusController(server).status();

        assertEquals(0, response.getCode());
        assertEquals(true, response.getData().get("enabled"));
        assertEquals("/mcp", response.getData().get("endpoint"));
        @SuppressWarnings("unchecked")
        List<String> tools = (List<String>) response.getData().get("tools");
        assertEquals(10, tools.size());
        assertTrue(tools.contains("context_build"));
        assertTrue(tools.contains("context_get_recent_dialogue"));
        assertTrue(tools.contains("context_get_current_page"));
        assertTrue(tools.contains("profile_list_categories"));
        assertTrue(tools.contains("profile_get_category_detail"));
        assertTrue(tools.contains("profile_search_relevant"));
    }
}
