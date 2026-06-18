package com.example.httpreading.domain.profile;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "profile_update_log")
public class ProfileUpdateLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "old_style_snapshot", columnDefinition = "json")
    private String oldStyleSnapshot;

    @Column(name = "new_style_snapshot", columnDefinition = "json")
    private String newStyleSnapshot;

    @Column(name = "old_reading_snapshot", columnDefinition = "json")
    private String oldReadingSnapshot;

    @Column(name = "new_reading_snapshot", columnDefinition = "json")
    private String newReadingSnapshot;

    @Column(name = "update_patch", columnDefinition = "json")
    private String updatePatch;

    @Column(name = "used_memory_ids", columnDefinition = "json")
    private String usedMemoryIds;

    @Column(name = "used_evidence_ids", columnDefinition = "json")
    private String usedEvidenceIds;

    @Column(name = "update_reason", columnDefinition = "text")
    private String updateReason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getOldStyleSnapshot() { return oldStyleSnapshot; }
    public void setOldStyleSnapshot(String oldStyleSnapshot) { this.oldStyleSnapshot = oldStyleSnapshot; }
    public String getNewStyleSnapshot() { return newStyleSnapshot; }
    public void setNewStyleSnapshot(String newStyleSnapshot) { this.newStyleSnapshot = newStyleSnapshot; }
    public String getOldReadingSnapshot() { return oldReadingSnapshot; }
    public void setOldReadingSnapshot(String oldReadingSnapshot) { this.oldReadingSnapshot = oldReadingSnapshot; }
    public String getNewReadingSnapshot() { return newReadingSnapshot; }
    public void setNewReadingSnapshot(String newReadingSnapshot) { this.newReadingSnapshot = newReadingSnapshot; }
    public String getUpdatePatch() { return updatePatch; }
    public void setUpdatePatch(String updatePatch) { this.updatePatch = updatePatch; }
    public String getUsedMemoryIds() { return usedMemoryIds; }
    public void setUsedMemoryIds(String usedMemoryIds) { this.usedMemoryIds = usedMemoryIds; }
    public String getUsedEvidenceIds() { return usedEvidenceIds; }
    public void setUsedEvidenceIds(String usedEvidenceIds) { this.usedEvidenceIds = usedEvidenceIds; }
    public String getUpdateReason() { return updateReason; }
    public void setUpdateReason(String updateReason) { this.updateReason = updateReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
