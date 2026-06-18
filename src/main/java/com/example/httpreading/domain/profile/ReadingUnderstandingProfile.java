package com.example.httpreading.domain.profile;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "reading_understanding_profile",
    uniqueConstraints = @UniqueConstraint(name = "uk_user_book_category", columnNames = {"user_id", "book_category"}))
public class ReadingUnderstandingProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "book_category", nullable = false, length = 128)
    private String bookCategory;

    @Column(name = "understanding_level", length = 64)
    private String understandingLevel;

    @Column(name = "learning_stage", length = 64)
    private String learningStage;

    @Column(columnDefinition = "json")
    private String strengths;

    @Column(columnDefinition = "json")
    private String weaknesses;

    @Column(name = "preferred_explanation", columnDefinition = "json")
    private String preferredExplanation;

    @Column(name = "background_needs", columnDefinition = "json")
    private String backgroundNeeds;

    @Column(name = "typical_questions", columnDefinition = "json")
    private String typicalQuestions;

    @Column(columnDefinition = "text")
    private String summary;

    private Double confidence = 0.5d;

    @Column(name = "last_evidence_id")
    private Long lastEvidenceId;

    @Column(name = "evidence_count")
    private Integer evidenceCount = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (confidence == null) {
            confidence = 0.5d;
        }
        if (evidenceCount == null) {
            evidenceCount = 0;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getBookCategory() { return bookCategory; }
    public void setBookCategory(String bookCategory) { this.bookCategory = bookCategory; }
    public String getUnderstandingLevel() { return understandingLevel; }
    public void setUnderstandingLevel(String understandingLevel) { this.understandingLevel = understandingLevel; }
    public String getLearningStage() { return learningStage; }
    public void setLearningStage(String learningStage) { this.learningStage = learningStage; }
    public String getStrengths() { return strengths; }
    public void setStrengths(String strengths) { this.strengths = strengths; }
    public String getWeaknesses() { return weaknesses; }
    public void setWeaknesses(String weaknesses) { this.weaknesses = weaknesses; }
    public String getPreferredExplanation() { return preferredExplanation; }
    public void setPreferredExplanation(String preferredExplanation) { this.preferredExplanation = preferredExplanation; }
    public String getBackgroundNeeds() { return backgroundNeeds; }
    public void setBackgroundNeeds(String backgroundNeeds) { this.backgroundNeeds = backgroundNeeds; }
    public String getTypicalQuestions() { return typicalQuestions; }
    public void setTypicalQuestions(String typicalQuestions) { this.typicalQuestions = typicalQuestions; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public Long getLastEvidenceId() { return lastEvidenceId; }
    public void setLastEvidenceId(Long lastEvidenceId) { this.lastEvidenceId = lastEvidenceId; }
    public Integer getEvidenceCount() { return evidenceCount; }
    public void setEvidenceCount(Integer evidenceCount) { this.evidenceCount = evidenceCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
