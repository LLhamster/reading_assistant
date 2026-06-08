package com.example.httpreading.mcp;

public class ExternalMcpCallResult {
    private final String serverName;
    private final String toolName;
    private final boolean ok;
    private final String content;
    private final String error;

    private ExternalMcpCallResult(String serverName, String toolName, boolean ok, String content, String error) {
        this.serverName = serverName;
        this.toolName = toolName;
        this.ok = ok;
        this.content = content;
        this.error = error;
    }

    public static ExternalMcpCallResult success(String serverName, String toolName, String content) {
        return new ExternalMcpCallResult(serverName, toolName, true, content, null);
    }

    public static ExternalMcpCallResult failure(String serverName, String toolName, String error) {
        return new ExternalMcpCallResult(serverName, toolName, false, null, error);
    }

    public String getServerName() {
        return serverName;
    }

    public String getToolName() {
        return toolName;
    }

    public boolean isOk() {
        return ok;
    }

    public String getContent() {
        return content;
    }

    public String getError() {
        return error;
    }

    public String ref() {
        return (ok ? "OK " : "FAIL ") + serverName + "/" + toolName + (ok ? "" : ": " + error);
    }
}
