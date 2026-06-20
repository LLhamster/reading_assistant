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
@Table(name = "user_knowledge_state",
    uniqueConstraints = @UniqueConstraint(name = "uk_user_domain_topic", columnNames = {"user_id", "domain", "topic"}))
public class UserKnowledgeState {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(nullable = false, length = 128)
    private String domain;

    @Column(nullable = false, length = 256)
    private String topic;

    @Column(name = "knowledge_type", nullable = false, length = 64)
    private String knowledgeType;

    @Column(nullable = false, length = 64)
    private String level;

    private Double confidence = 0.5d;

    @Column(name = "mastered_evidence", columnDefinition = "text")
    private String masteredEvidence;

    @Column(name = "weakness_evidence", columnDefinition = "text")
    private String weaknessEvidence;

    @Column(name = "related_book_id")
    private Long relatedBookId;

    @Column(name = "related_book_title")
    private String relatedBookTitle;

    @Column(name = "related_chapter_index")
    private Integer relatedChapterIndex;

    @Column(name = "source_evidence_ids", columnDefinition = "json")
    private String sourceEvidenceIds;

    @Column(columnDefinition = "text")
    private String summary;

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
        if (knowledgeType == null || knowledgeType.isBlank()) {
            knowledgeType = "other";
        }
        if (level == null || level.isBlank()) {
            level = "unknown";
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
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getKnowledgeType() { return knowledgeType; }
    public void setKnowledgeType(String knowledgeType) { this.knowledgeType = knowledgeType; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public String getMasteredEvidence() { return masteredEvidence; }
    public void setMasteredEvidence(String masteredEvidence) { this.masteredEvidence = masteredEvidence; }
    public String getWeaknessEvidence() { return weaknessEvidence; }
    public void setWeaknessEvidence(String weaknessEvidence) { this.weaknessEvidence = weaknessEvidence; }
    public Long getRelatedBookId() { return relatedBookId; }
    public void setRelatedBookId(Long relatedBookId) { this.relatedBookId = relatedBookId; }
    public String getRelatedBookTitle() { return relatedBookTitle; }
    public void setRelatedBookTitle(String relatedBookTitle) { this.relatedBookTitle = relatedBookTitle; }
    public Integer getRelatedChapterIndex() { return relatedChapterIndex; }
    public void setRelatedChapterIndex(Integer relatedChapterIndex) { this.relatedChapterIndex = relatedChapterIndex; }
    public String getSourceEvidenceIds() { return sourceEvidenceIds; }
    public void setSourceEvidenceIds(String sourceEvidenceIds) { this.sourceEvidenceIds = sourceEvidenceIds; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
