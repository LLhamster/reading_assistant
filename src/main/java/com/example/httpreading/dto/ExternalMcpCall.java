package com.example.httpreading.dto;

import java.util.HashMap;
import java.util.Map;

public class ExternalMcpCall {
    private String serverName;
    private String toolName;
    private Map<String, Object> arguments = new HashMap<>();

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public void setArguments(Map<String, Object> arguments) {
        this.arguments = arguments == null ? new HashMap<>() : arguments;
    }
}
