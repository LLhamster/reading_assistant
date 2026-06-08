package com.example.httpreading.service.ai;

import java.util.List;

import com.example.httpreading.dto.AiChatInteraction;
import com.example.httpreading.mcp.ExternalMcpCallResult;

public record ToolExecutionResult(List<ExternalMcpCallResult> results,
                                  List<String> planRefs,
                                  String guidance,
                                  String status,
                                  AiChatInteraction interaction) {
    public ToolExecutionResult {
        results = results == null ? List.of() : List.copyOf(results);
        planRefs = planRefs == null ? List.of() : List.copyOf(planRefs);
        guidance = guidance == null ? "" : guidance;
        status = status == null || status.isBlank() ? "completed" : status;
    }

    public static ToolExecutionResult completed(List<ExternalMcpCallResult> results, List<String> planRefs) {
        return new ToolExecutionResult(results, planRefs, "", "completed", null);
    }

    public boolean needsConfirmation() {
        return "needs_confirmation".equals(status);
    }
}
