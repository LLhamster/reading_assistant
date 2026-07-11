package com.example.httpreading.domain.cognition;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "concept_merge_relation", indexes = {
    @Index(name = "idx_merge_source", columnList = "source_concept_id"),
    @Index(name = "idx_merge_target", columnList = "target_concept_id")
})
public class ConceptMergeRelation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_concept_id", nullable = false)
    private Long sourceConceptId;

    @Column(name = "target_concept_id", nullable = false)
    private Long targetConceptId;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSourceConceptId() { return sourceConceptId; }
    public void setSourceConceptId(Long sourceConceptId) { this.sourceConceptId = sourceConceptId; }
    public Long getTargetConceptId() { return targetConceptId; }
    public void setTargetConceptId(Long targetConceptId) { this.targetConceptId = targetConceptId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
