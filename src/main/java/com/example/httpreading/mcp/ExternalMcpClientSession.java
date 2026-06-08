package com.example.httpreading.mcp;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;

public interface ExternalMcpClientSession extends AutoCloseable {
    void initialize();

    ListToolsResult listTools();

    CallToolResult callTool(CallToolRequest request);

    @Override
    void close();
}
