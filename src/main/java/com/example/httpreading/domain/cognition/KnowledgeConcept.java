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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "knowledge_concept", indexes = {
    @Index(name = "idx_concept_normalized_name", columnList = "normalized_name"),
    @Index(name = "idx_concept_book_status", columnList = "book_id,status")
})
public class KnowledgeConcept {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "canonical_name", nullable = false)
    private String canonicalName;

    @Column(name = "normalized_name", nullable = false)
    private String normalizedName;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "book_id")
    private Long bookId;

    @Column(name = "first_chapter_index")
    private Integer firstChapterIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ConceptStatus status;

    @Column(name = "merged_to_concept_id")
    private Long mergedToConceptId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) {
            status = ConceptStatus.CANDIDATE;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCanonicalName() { return canonicalName; }
    public void setCanonicalName(String canonicalName) { this.canonicalName = canonicalName; }
    public String getNormalizedName() { return normalizedName; }
    public void setNormalizedName(String normalizedName) { this.normalizedName = normalizedName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Long getBookId() { return bookId; }
    public void setBookId(Long bookId) { this.bookId = bookId; }
    public Integer getFirstChapterIndex() { return firstChapterIndex; }
    public void setFirstChapterIndex(Integer firstChapterIndex) { this.firstChapterIndex = firstChapterIndex; }
    public ConceptStatus getStatus() { return status; }
    public void setStatus(ConceptStatus status) { this.status = status; }
    public Long getMergedToConceptId() { return mergedToConceptId; }
    public void setMergedToConceptId(Long mergedToConceptId) { this.mergedToConceptId = mergedToConceptId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
