package com.example.httpreading.mq.cognition;

public class LearningEvent {
    private String eventId;
    private String userId;
    private Long bookId;
    private Integer chapterIndex;
    private String sessionId;
    private String question;
    private String selectedText;
    private String selectedContext;
    private String chapterTitle;
    private String chapterContent;
    private String recentDialogueSummary;

    public LearningEvent() {
    }

    public LearningEvent(String eventId,
                         String userId,
                         Long bookId,
                         Integer chapterIndex,
                         String sessionId,
                         String question,
                         String selectedText,
                         String selectedContext,
                         String chapterTitle,
                         String chapterContent,
                         String recentDialogueSummary) {
        this.eventId = eventId;
        this.userId = userId;
        this.bookId = bookId;
        this.chapterIndex = chapterIndex;
        this.sessionId = sessionId;
        this.question = question;
        this.selectedText = selectedText;
        this.selectedContext = selectedContext;
        this.chapterTitle = chapterTitle;
        this.chapterContent = chapterContent;
        this.recentDialogueSummary = recentDialogueSummary;
    }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Long getBookId() { return bookId; }
    public void setBookId(Long bookId) { this.bookId = bookId; }
    public Integer getChapterIndex() { return chapterIndex; }
    public void setChapterIndex(Integer chapterIndex) { this.chapterIndex = chapterIndex; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getSelectedText() { return selectedText; }
    public void setSelectedText(String selectedText) { this.selectedText = selectedText; }
    public String getSelectedContext() { return selectedContext; }
    public void setSelectedContext(String selectedContext) { this.selectedContext = selectedContext; }
    public String getChapterTitle() { return chapterTitle; }
    public void setChapterTitle(String chapterTitle) { this.chapterTitle = chapterTitle; }
    public String getChapterContent() { return chapterContent; }
    public void setChapterContent(String chapterContent) { this.chapterContent = chapterContent; }
    public String getRecentDialogueSummary() { return recentDialogueSummary; }
    public void setRecentDialogueSummary(String recentDialogueSummary) { this.recentDialogueSummary = recentDialogueSummary; }
}
