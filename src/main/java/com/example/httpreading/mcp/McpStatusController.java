package com.example.httpreading.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

import com.example.httpreading.api.CommonResponse;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mcp")
public class McpStatusController {
    private final McpSyncServer mcpSyncServer;

    public McpStatusController(McpSyncServer mcpSyncServer) {
        this.mcpSyncServer = mcpSyncServer;
    }

    @GetMapping("/status")
    public CommonResponse<Map<String, Object>> status() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", true);
        data.put("endpoint", "/mcp");
        data.put("serverName", mcpSyncServer.getServerInfo().name());
        data.put("serverVersion", mcpSyncServer.getServerInfo().version());
        data.put("tools", mcpSyncServer.listTools().stream()
            .map(McpSchema.Tool::name)
            .toList());
        data.put("note", "Use an MCP Client to call /mcp. Browser GET /mcp is not a tools/list request.");
        return CommonResponse.success(data);
    }
}
