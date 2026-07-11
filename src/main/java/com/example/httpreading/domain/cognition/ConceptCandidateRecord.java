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

@Entity
@Table(name = "concept_candidate_record", indexes = {
    @Index(name = "idx_candidate_event", columnList = "event_id"),
    @Index(name = "idx_candidate_status_created", columnList = "status,created_at")
})
public class ConceptCandidateRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "candidate_name")
    private String candidateName;

    @Column(name = "matched_concept_id")
    private Long matchedConceptId;

    private Double confidence;

    @Column(name = "model_score")
    private Double modelScore;

    @Column(name = "lexical_score")
    private Double lexicalScore;

    @Column(name = "context_score")
    private Double contextScore;

    @Column(name = "history_score")
    private Double historyScore;

    @Column(name = "candidate_gap_score")
    private Double candidateGapScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ConceptCandidateStatus status;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        if (status == null) {
            status = ConceptCandidateStatus.PENDING;
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getCandidateName() { return candidateName; }
    public void setCandidateName(String candidateName) { this.candidateName = candidateName; }
    public Long getMatchedConceptId() { return matchedConceptId; }
    public void setMatchedConceptId(Long matchedConceptId) { this.matchedConceptId = matchedConceptId; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public Double getModelScore() { return modelScore; }
    public void setModelScore(Double modelScore) { this.modelScore = modelScore; }
    public Double getLexicalScore() { return lexicalScore; }
    public void setLexicalScore(Double lexicalScore) { this.lexicalScore = lexicalScore; }
    public Double getContextScore() { return contextScore; }
    public void setContextScore(Double contextScore) { this.contextScore = contextScore; }
    public Double getHistoryScore() { return historyScore; }
    public void setHistoryScore(Double historyScore) { this.historyScore = historyScore; }
    public Double getCandidateGapScore() { return candidateGapScore; }
    public void setCandidateGapScore(Double candidateGapScore) { this.candidateGapScore = candidateGapScore; }
    public ConceptCandidateStatus getStatus() { return status; }
    public void setStatus(ConceptCandidateStatus status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
