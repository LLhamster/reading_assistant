package com.example.httpreading.dto;

import java.util.List;

/**
 * AI 问答响应（包含回答 + 引用来源）
 */
public class AiChatResponse {

    private String answer;
    private List<String> sources;
    private Integer contextId;
    private String sessionId;
    private List<String> memoryRefs;
    private List<String> externalMcpRefs;
    private List<String> externalMcpPlanRefs;
    private String status = "completed";
    private AiChatInteraction interaction;

    public AiChatResponse() {}

    public AiChatResponse(String answer, List<String> sources) {
        this.answer = answer;
        this.sources = sources;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public List<String> getSources() {
        return sources;
    }

    public void setSources(List<String> sources) {
        this.sources = sources;
    }

    public Integer getContextId() {
        return contextId;
    }

    public void setContextId(Integer contextId) {
        this.contextId = contextId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public List<String> getMemoryRefs() {
        return memoryRefs;
    }

    public void setMemoryRefs(List<String> memoryRefs) {
        this.memoryRefs = memoryRefs;
    }

    public List<String> getExternalMcpRefs() {
        return externalMcpRefs;
    }

    public void setExternalMcpRefs(List<String> externalMcpRefs) {
        this.externalMcpRefs = externalMcpRefs;
    }

    public List<String> getExternalMcpPlanRefs() {
        return externalMcpPlanRefs;
    }

    public void setExternalMcpPlanRefs(List<String> externalMcpPlanRefs) {
        this.externalMcpPlanRefs = externalMcpPlanRefs;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status == null || status.isBlank() ? "completed" : status;
    }

    public AiChatInteraction getInteraction() {
        return interaction;
    }

    public void setInteraction(AiChatInteraction interaction) {
        this.interaction = interaction;
    }
}
