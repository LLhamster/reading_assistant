package com.example.httpreading.mcp;

import java.time.Duration;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import org.springframework.stereotype.Component;

@Component
public class SdkExternalMcpClientFactory implements ExternalMcpClientFactory {
    private final McpJsonMapper mcpJsonMapper;

    public SdkExternalMcpClientFactory(McpJsonMapper mcpJsonMapper) {
        this.mcpJsonMapper = mcpJsonMapper;
    }

    @Override
    public ExternalMcpClientSession create(ExternalMcpClientProperties.Server server) {
        Duration timeout = Duration.ofSeconds(Math.max(1, server.getTimeoutSeconds()));
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(server.getUrl())
            .jsonMapper(mcpJsonMapper)
            .connectTimeout(timeout)
            .httpRequestCustomizer((builder, method, uri, body, context) ->
                server.getHeaders().forEach((name, value) -> {
                    if (name != null && value != null && !name.isBlank() && !value.isBlank()) {
                        builder.header(name, value);
                    }
                }))
            .build();

        McpSyncClient client = McpClient.sync(transport)
            .clientInfo(new Implementation("http-reading-mcp-client", "0.0.1-SNAPSHOT"))
            .requestTimeout(timeout)
            .initializationTimeout(timeout)
            .build();
        return new SdkExternalMcpClientSession(client);
    }

    private static class SdkExternalMcpClientSession implements ExternalMcpClientSession {
        private final McpSyncClient client;

        private SdkExternalMcpClientSession(McpSyncClient client) {
            this.client = client;
        }

        @Override
        public void initialize() {
            if (!client.isInitialized()) {
                client.initialize();
            }
        }

        @Override
        public ListToolsResult listTools() {
            return client.listTools();
        }

        @Override
        public CallToolResult callTool(CallToolRequest request) {
            return client.callTool(request);
        }

        @Override
        public void close() {
            client.close();
        }
    }
}
