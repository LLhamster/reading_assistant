package com.example.httpreading.mcp;

import java.util.ArrayList;
import java.util.List;

import com.example.httpreading.dto.AiChatInteraction;
import com.example.httpreading.dto.AiChatRequest;

public class ExternalMcpAgentResult {
    private final List<ExternalMcpCallResult> results;
    private final List<String> refs;
    private final String guidance;
    private final String status;
    private final AiChatInteraction interaction;
    private final AiChatRequest finalRequest;

    public ExternalMcpAgentResult(List<ExternalMcpCallResult> results,
                                  List<String> refs,
                                  String guidance) {
        this(results, refs, guidance, "completed", null, null);
    }

    public ExternalMcpAgentResult(List<ExternalMcpCallResult> results,
                                  List<String> refs,
                                  String guidance,
                                  String status,
                                  AiChatInteraction interaction,
                                  AiChatRequest finalRequest) {
        this.results = results == null ? new ArrayList<>() : results;
        this.refs = refs == null ? new ArrayList<>() : refs;
        this.guidance = guidance == null ? "" : guidance;
        this.status = status == null || status.isBlank() ? "completed" : status;
        this.interaction = interaction;
        this.finalRequest = finalRequest;
    }

    public List<ExternalMcpCallResult> getResults() {
        return results;
    }

    public List<String> getRefs() {
        return refs;
    }

    public String getGuidance() {
        return guidance;
    }

    public String getStatus() {
        return status;
    }

    public boolean needsConfirmation() {
        return "needs_confirmation".equals(status);
    }

    public AiChatInteraction getInteraction() {
        return interaction;
    }

    public AiChatRequest getFinalRequest() {
        return finalRequest;
    }
}
