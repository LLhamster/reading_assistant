package com.example.httpreading.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class AiChatRequest {

    @NotNull(message = "bookId 不能为空")
    @Positive(message = "bookId 必须为正数")
    private Long bookId;

    @NotNull(message = "chapterIndex 不能为空")
    @Positive(message = "chapterIndex 必须为正数")
    private Integer chapterIndex;

    @NotBlank(message = "问题内容不能为空")
    private String question;

    private String userId;
    private String sessionId;
    private Integer contextId;
    private Integer topK;
    private Boolean enableMemory;
    private Boolean enableRag;
    private Boolean enableExternalMcp;
    private List<ExternalMcpCall> externalMcpCalls;
    private String chapterTitle;
    private String chapterContent;
    private String selectedText;
    private String selectedContext;
    private String confirmationId;
    private String selectedOptionId;
    private String customAnswer;

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }

    public Integer getChapterIndex() {
        return chapterIndex;
    }

    public void setChapterIndex(Integer chapterIndex) {
        this.chapterIndex = chapterIndex;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Integer getContextId() {
        return contextId;
    }

    public void setContextId(Integer contextId) {
        this.contextId = contextId;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public Boolean getEnableMemory() {
        return enableMemory;
    }

    public void setEnableMemory(Boolean enableMemory) {
        this.enableMemory = enableMemory;
    }

    public Boolean getEnableRag() {
        return enableRag;
    }

    public void setEnableRag(Boolean enableRag) {
        this.enableRag = enableRag;
    }

    public Boolean getEnableExternalMcp() {
        return enableExternalMcp;
    }

    public void setEnableExternalMcp(Boolean enableExternalMcp) {
        this.enableExternalMcp = enableExternalMcp;
    }

    public List<ExternalMcpCall> getExternalMcpCalls() {
        return externalMcpCalls;
    }

    public void setExternalMcpCalls(List<ExternalMcpCall> externalMcpCalls) {
        this.externalMcpCalls = externalMcpCalls;
    }

    public String getChapterTitle() {
        return chapterTitle;
    }

    public void setChapterTitle(String chapterTitle) {
        this.chapterTitle = chapterTitle;
    }

    public String getChapterContent() {
        return chapterContent;
    }

    public void setChapterContent(String chapterContent) {
        this.chapterContent = chapterContent;
    }

    public String getSelectedText() {
        return selectedText;
    }

    public void setSelectedText(String selectedText) {
        this.selectedText = selectedText;
    }

    public String getSelectedContext() {
        return selectedContext;
    }

    public void setSelectedContext(String selectedContext) {
        this.selectedContext = selectedContext;
    }

    public String getConfirmationId() {
        return confirmationId;
    }

    public void setConfirmationId(String confirmationId) {
        this.confirmationId = confirmationId;
    }

    public String getSelectedOptionId() {
        return selectedOptionId;
    }

    public void setSelectedOptionId(String selectedOptionId) {
        this.selectedOptionId = selectedOptionId;
    }

    public String getCustomAnswer() {
        return customAnswer;
    }

    public void setCustomAnswer(String customAnswer) {
        this.customAnswer = customAnswer;
    }

    public String resolvedUserId() {
        return userId == null || userId.isBlank() ? "default_user" : userId;
    }

    public String resolvedSessionId() {
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionId;
        }
        return "book_" + bookId + "_chapter_" + chapterIndex;
    }

    public int resolvedTopK() {
        return topK == null || topK <= 0 ? 5 : topK;
    }

    public boolean isMemoryEnabled() {
        return enableMemory == null || enableMemory;
    }

    public boolean isRagEnabled() {
        return enableRag == null || enableRag;
    }

    public boolean isExternalMcpEnabled() {
        return Boolean.TRUE.equals(enableExternalMcp);
    }

    public boolean hasConfirmationId() {
        return confirmationId != null && !confirmationId.isBlank();
    }
}
