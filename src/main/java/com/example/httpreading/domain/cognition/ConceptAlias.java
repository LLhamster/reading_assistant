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
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "concept_alias",
    uniqueConstraints = @UniqueConstraint(name = "uk_alias_concept_normalized",
        columnNames = {"concept_id", "normalized_alias_name"}),
    indexes = @Index(name = "idx_alias_normalized_name", columnList = "normalized_alias_name"))
public class ConceptAlias {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "concept_id", nullable = false)
    private Long conceptId;

    @Column(name = "alias_name", nullable = false)
    private String aliasName;

    @Column(name = "normalized_alias_name", nullable = false)
    private String normalizedAliasName;

    @Column(length = 64)
    private String source;

    private Double confidence;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        if (confidence == null) {
            confidence = 0.5d;
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getConceptId() { return conceptId; }
    public void setConceptId(Long conceptId) { this.conceptId = conceptId; }
    public String getAliasName() { return aliasName; }
    public void setAliasName(String aliasName) { this.aliasName = aliasName; }
    public String getNormalizedAliasName() { return normalizedAliasName; }
    public void setNormalizedAliasName(String normalizedAliasName) { this.normalizedAliasName = normalizedAliasName; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
