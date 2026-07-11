package com.example.httpreading.domain.cognition;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "concept_resolution_record",
    uniqueConstraints = @UniqueConstraint(name = "uk_concept_resolution_event", columnNames = "event_id"),
    indexes = @Index(name = "idx_resolution_user_book", columnList = "user_id,book_id,chapter_index"))
public class ConceptResolutionRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "book_id")
    private Long bookId;

    @Column(name = "chapter_index")
    private Integer chapterIndex;

    @Column(name = "session_id", length = 128)
    private String sessionId;

    @Column(name = "primary_concept_name")
    private String primaryConceptName;

    @Column(name = "matched_concept_id")
    private Long matchedConceptId;

    @Column(name = "candidate_concepts_json", columnDefinition = "json")
    private String candidateConceptsJson;

    private Double confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "confidence_level", length = 16)
    private ConfidenceLevel confidenceLevel;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private ConceptResolutionDecision decision;

    @Column(name = "score_breakdown_json", columnDefinition = "json")
    private String scoreBreakdownJson;

    @Column(name = "context_evidence_json", columnDefinition = "json")
    private String contextEvidenceJson;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(columnDefinition = "text")
    private String question;

    @Column(name = "selected_text", columnDefinition = "text")
    private String selectedText;

    @Column(name = "selected_context", columnDefinition = "text")
    private String selectedContext;

    @Column(name = "recent_dialogue_summary", columnDefinition = "text")
    private String recentDialogueSummary;

    @Column(name = "model_name", length = 128)
    private String modelName;

    @Column(name = "prompt_version", length = 64)
    private String promptVersion;

    @Column(name = "analyzer_version", length = 64)
    private String analyzerVersion;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
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
    public String getPrimaryConceptName() { return primaryConceptName; }
    public void setPrimaryConceptName(String primaryConceptName) { this.primaryConceptName = primaryConceptName; }
    public Long getMatchedConceptId() { return matchedConceptId; }
    public void setMatchedConceptId(Long matchedConceptId) { this.matchedConceptId = matchedConceptId; }
    public String getCandidateConceptsJson() { return candidateConceptsJson; }
    public void setCandidateConceptsJson(String candidateConceptsJson) { this.candidateConceptsJson = candidateConceptsJson; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public ConfidenceLevel getConfidenceLevel() { return confidenceLevel; }
    public void setConfidenceLevel(ConfidenceLevel confidenceLevel) { this.confidenceLevel = confidenceLevel; }
    public ConceptResolutionDecision getDecision() { return decision; }
    public void setDecision(ConceptResolutionDecision decision) { this.decision = decision; }
    public String getScoreBreakdownJson() { return scoreBreakdownJson; }
    public void setScoreBreakdownJson(String scoreBreakdownJson) { this.scoreBreakdownJson = scoreBreakdownJson; }
    public String getContextEvidenceJson() { return contextEvidenceJson; }
    public void setContextEvidenceJson(String contextEvidenceJson) { this.contextEvidenceJson = contextEvidenceJson; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getSelectedText() { return selectedText; }
    public void setSelectedText(String selectedText) { this.selectedText = selectedText; }
    public String getSelectedContext() { return selectedContext; }
    public void setSelectedContext(String selectedContext) { this.selectedContext = selectedContext; }
    public String getRecentDialogueSummary() { return recentDialogueSummary; }
    public void setRecentDialogueSummary(String recentDialogueSummary) { this.recentDialogueSummary = recentDialogueSummary; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public String getAnalyzerVersion() { return analyzerVersion; }
    public void setAnalyzerVersion(String analyzerVersion) { this.analyzerVersion = analyzerVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
