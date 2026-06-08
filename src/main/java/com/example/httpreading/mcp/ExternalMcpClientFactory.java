package com.example.httpreading.mcp;

public interface ExternalMcpClientFactory {
    ExternalMcpClientSession create(ExternalMcpClientProperties.Server server);
}
