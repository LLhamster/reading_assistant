package com.example.httpreading.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

import com.example.httpreading.dto.ExternalMcpCall;

public record ExternalMcpAgentObservation(int round,
                                          ExternalMcpCall call,
                                          ExternalMcpCallResult result) {
    public Map<String, Object> toMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("round", round);
        data.put("serverName", call.getServerName());
        data.put("toolName", call.getToolName());
        data.put("arguments", call.getArguments());
        data.put("ok", result.isOk());
        data.put("content", result.getContent());
        data.put("error", result.getError());
        return data;
    }
}
