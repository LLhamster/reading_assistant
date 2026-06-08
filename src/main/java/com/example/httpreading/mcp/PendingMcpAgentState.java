package com.example.httpreading.mcp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.httpreading.dto.AiChatRequest;

public class PendingMcpAgentState {
    private final AiChatRequest originalRequest;
    private final String planningContext;
    private final List<ExternalMcpAgentObservation> observations = new ArrayList<>();
    private final List<ExternalMcpCallResult> results = new ArrayList<>();
    private final List<String> refs = new ArrayList<>();
    private final Set<String> executedCalls = new HashSet<>();
    private final Set<String> rejectedRepositoryTargets = new HashSet<>();
    private int nextRound = 1;
    private int toolCalls;
    private String confirmedRepositoryOwner;
    private String confirmedRepositoryRepo;
    private String routedServerName = "";

    public PendingMcpAgentState(AiChatRequest originalRequest, String planningContext) {
        this.originalRequest = originalRequest;
        this.planningContext = planningContext;
    }

    public AiChatRequest getOriginalRequest() {
        return originalRequest;
    }

    public String getPlanningContext() {
        return planningContext;
    }

    public List<ExternalMcpAgentObservation> getObservations() {
        return observations;
    }

    public List<ExternalMcpCallResult> getResults() {
        return results;
    }

    public List<String> getRefs() {
        return refs;
    }

    public Set<String> getExecutedCalls() {
        return executedCalls;
    }

    public Set<String> getRejectedRepositoryTargets() {
        return rejectedRepositoryTargets;
    }

    public int getNextRound() {
        return nextRound;
    }

    public void setNextRound(int nextRound) {
        this.nextRound = nextRound;
    }

    public int getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(int toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getConfirmedRepositoryOwner() {
        return confirmedRepositoryOwner;
    }

    public String getConfirmedRepositoryRepo() {
        return confirmedRepositoryRepo;
    }

    public void setConfirmedRepositoryTarget(String owner, String repo) {
        this.confirmedRepositoryOwner = owner == null ? "" : owner.trim();
        this.confirmedRepositoryRepo = repo == null ? "" : repo.trim();
    }

    public boolean hasConfirmedRepositoryTarget() {
        return confirmedRepositoryOwner != null && !confirmedRepositoryOwner.isBlank()
            && confirmedRepositoryRepo != null && !confirmedRepositoryRepo.isBlank();
    }

    public String getRoutedServerName() {
        return routedServerName == null ? "" : routedServerName;
    }

    public void setRoutedServerName(String routedServerName) {
        this.routedServerName = routedServerName == null ? "" : routedServerName.trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
