package com.example.httpreading.dto;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class AiChatInteraction {
    private String confirmationId;
    private String question;
    private List<AiChatInteractionOption> options = new ArrayList<>();
    private boolean allowCustomAnswer;
    private OffsetDateTime expiresAt;

    public String getConfirmationId() {
        return confirmationId;
    }

    public void setConfirmationId(String confirmationId) {
        this.confirmationId = confirmationId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public List<AiChatInteractionOption> getOptions() {
        return options;
    }

    public void setOptions(List<AiChatInteractionOption> options) {
        this.options = options == null ? new ArrayList<>() : options;
    }

    public boolean isAllowCustomAnswer() {
        return allowCustomAnswer;
    }

    public void setAllowCustomAnswer(boolean allowCustomAnswer) {
        this.allowCustomAnswer = allowCustomAnswer;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
