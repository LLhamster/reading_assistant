package com.example.httpreading.mcp;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class PendingMcpInteraction {
    private final String confirmationId;
    private final String userId;
    private final String sessionId;
    private final String question;
    private final List<ExternalMcpAgentOption> options;
    private final boolean allowCustomAnswer;
    private final OffsetDateTime expiresAt;
    private final PendingMcpAgentState state;

    public PendingMcpInteraction(String confirmationId,
                                 String userId,
                                 String sessionId,
                                 String question,
                                 List<ExternalMcpAgentOption> options,
                                 boolean allowCustomAnswer,
                                 OffsetDateTime expiresAt,
                                 PendingMcpAgentState state) {
        this.confirmationId = confirmationId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.question = question;
        this.options = options == null ? new ArrayList<>() : new ArrayList<>(options);
        this.allowCustomAnswer = allowCustomAnswer;
        this.expiresAt = expiresAt;
        this.state = state;
    }

    public String getConfirmationId() {
        return confirmationId;
    }

    public String getUserId() {
        return userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getQuestion() {
        return question;
    }

    public List<ExternalMcpAgentOption> getOptions() {
        return options;
    }

    public boolean isAllowCustomAnswer() {
        return allowCustomAnswer;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public PendingMcpAgentState getState() {
        return state;
    }

    public boolean isExpired(OffsetDateTime now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}
