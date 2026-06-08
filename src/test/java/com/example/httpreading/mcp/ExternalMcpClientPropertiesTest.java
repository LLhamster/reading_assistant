package com.example.httpreading.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ExternalMcpClientPropertiesTest {

    @Test
    void bindsServersAndHeaders() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("mcp.client.servers[0].name", "web-search");
        source.put("mcp.client.servers[0].description", "Search web pages");
        source.put("mcp.client.servers[0].url", "http://localhost:9000/mcp");
        source.put("mcp.client.servers[0].enabled", "true");
        source.put("mcp.client.servers[0].timeout-seconds", "8");
        source.put("mcp.client.servers[0].headers.Authorization", "Bearer abc");
        source.put("mcp.client.servers[0].allowed-tools[0]", "get_me");
        source.put("mcp.client.servers[0].allowed-tools[1]", "get_file_contents");
        source.put("mcp.client.servers[1].name", "disabled");
        source.put("mcp.client.servers[1].url", "http://localhost:9001/mcp");
        source.put("mcp.client.servers[1].enabled", "false");

        ExternalMcpClientProperties properties = new Binder(new MapConfigurationPropertySource(source))
            .bind("mcp.client", Bindable.of(ExternalMcpClientProperties.class))
            .orElseThrow(() -> new AssertionError("mcp.client should bind"));

        assertEquals(2, properties.getServers().size());
        ExternalMcpClientProperties.Server server = properties.getServers().get(0);
        assertEquals("web-search", server.getName());
        assertEquals("Search web pages", server.getDescription());
        assertEquals("http://localhost:9000/mcp", server.getUrl());
        assertEquals(8, server.getTimeoutSeconds());
        assertEquals("Bearer abc", server.getHeaders().get("Authorization"));
        assertEquals(List.of("get_me", "get_file_contents"), server.getAllowedTools());
        assertTrue(server.isEnabled());
    }
}
