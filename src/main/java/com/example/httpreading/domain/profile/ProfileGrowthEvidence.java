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
@Table(name = "profile_growth_evidence")
public class ProfileGrowthEvidence {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "evidence_domain", nullable = false, length = 64)
    private String evidenceDomain;

    @Column(name = "evidence_type", nullable = false, length = 64)
    private String evidenceType;

    @Column(name = "book_category", length = 128)
    private String bookCategory;

    @Column(name = "related_book_id")
    private Long relatedBookId;

    @Column(name = "related_book_title")
    private String relatedBookTitle;

    @Column(name = "related_chapter_index")
    private Integer relatedChapterIndex;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    private Double importance = 0.5d;

    @Column(length = 32)
    private String status = "active";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (importance == null) {
            importance = 0.5d;
        }
        if (status == null || status.isBlank()) {
            status = "active";
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
    public String getEvidenceDomain() { return evidenceDomain; }
    public void setEvidenceDomain(String evidenceDomain) { this.evidenceDomain = evidenceDomain; }
    public String getEvidenceType() { return evidenceType; }
    public void setEvidenceType(String evidenceType) { this.evidenceType = evidenceType; }
    public String getBookCategory() { return bookCategory; }
    public void setBookCategory(String bookCategory) { this.bookCategory = bookCategory; }
    public Long getRelatedBookId() { return relatedBookId; }
    public void setRelatedBookId(Long relatedBookId) { this.relatedBookId = relatedBookId; }
    public String getRelatedBookTitle() { return relatedBookTitle; }
    public void setRelatedBookTitle(String relatedBookTitle) { this.relatedBookTitle = relatedBookTitle; }
    public Integer getRelatedChapterIndex() { return relatedChapterIndex; }
    public void setRelatedChapterIndex(Integer relatedChapterIndex) { this.relatedChapterIndex = relatedChapterIndex; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Double getImportance() { return importance; }
    public void setImportance(Double importance) { this.importance = importance; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
