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

@Entity
@Table(name = "user_style_profile")
public class UserStyleProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true, length = 64)
    private String userId;

    @Column(name = "explanation_style", length = 128)
    private String explanationStyle;

    @Column(name = "preferred_depth", length = 64)
    private String preferredDepth;

    @Column(name = "prefers_examples")
    private Boolean prefersExamples = false;

    @Column(name = "prefers_storytelling")
    private Boolean prefersStorytelling = false;

    @Column(name = "prefers_step_by_step")
    private Boolean prefersStepByStep = false;

    @Column(columnDefinition = "json")
    private String avoidance;

    @Column(columnDefinition = "text")
    private String summary;

    private Double confidence = 0.5d;

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
        if (prefersExamples == null) {
            prefersExamples = false;
        }
        if (prefersStorytelling == null) {
            prefersStorytelling = false;
        }
        if (prefersStepByStep == null) {
            prefersStepByStep = false;
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
    public String getExplanationStyle() { return explanationStyle; }
    public void setExplanationStyle(String explanationStyle) { this.explanationStyle = explanationStyle; }
    public String getPreferredDepth() { return preferredDepth; }
    public void setPreferredDepth(String preferredDepth) { this.preferredDepth = preferredDepth; }
    public Boolean getPrefersExamples() { return prefersExamples; }
    public void setPrefersExamples(Boolean prefersExamples) { this.prefersExamples = prefersExamples; }
    public Boolean getPrefersStorytelling() { return prefersStorytelling; }
    public void setPrefersStorytelling(Boolean prefersStorytelling) { this.prefersStorytelling = prefersStorytelling; }
    public Boolean getPrefersStepByStep() { return prefersStepByStep; }
    public void setPrefersStepByStep(Boolean prefersStepByStep) { this.prefersStepByStep = prefersStepByStep; }
    public String getAvoidance() { return avoidance; }
    public void setAvoidance(String avoidance) { this.avoidance = avoidance; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
