package com.example.httpreading.mcp;

import java.util.ArrayList;
import java.util.List;

import com.example.httpreading.dto.ExternalMcpCall;

public class ExternalMcpAgentDecision {
    private final String status;
    private final String assessment;
    private final String reasoningSummary;
    private final ExternalMcpCall call;
    private final String message;
    private final List<ExternalMcpAgentOption> options;

    public ExternalMcpAgentDecision(String status,
                                    String assessment,
                                    String reasoningSummary,
                                    ExternalMcpCall call,
                                    String message) {
        this(status, assessment, reasoningSummary, call, message, List.of());
    }

    public ExternalMcpAgentDecision(String status,
                                    String assessment,
                                    String reasoningSummary,
                                    ExternalMcpCall call,
                                    String message,
                                    List<ExternalMcpAgentOption> options) {
        this.status = status == null ? "failed" : status;
        this.assessment = assessment == null ? "" : assessment;
        this.reasoningSummary = reasoningSummary == null ? "" : reasoningSummary;
        this.call = call;
        this.message = message == null ? "" : message;
        this.options = options == null ? new ArrayList<>() : new ArrayList<>(options);
    }

    public static ExternalMcpAgentDecision failed(String message) {
        return new ExternalMcpAgentDecision("failed", "", "", null, message);
    }

    public String getStatus() {
        return status;
    }

    public String getAssessment() {
        return assessment;
    }

    public String getReasoningSummary() {
        return reasoningSummary;
    }

    public ExternalMcpCall getCall() {
        return call;
    }

    public String getMessage() {
        return message;
    }

    public List<ExternalMcpAgentOption> getOptions() {
        return options;
    }
}
