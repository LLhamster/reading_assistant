package com.example.httpreading.context.model;

public class ContextSnapshot {
    private Integer snapshotId;
    private Integer contextId;
    private String userId;
    private String version;
    private String variables;
    private Long timestamp;
    private String description;

    public ContextSnapshot() {
    }

    public ContextSnapshot(Integer snapshotId,
                           Integer contextId,
                           String userId,
                           String version,
                           String variables,
                           Long timestamp,
                           String description) {
        this.snapshotId = snapshotId;
        this.contextId = contextId;
        this.userId = userId;
        this.version = version;
        this.variables = variables;
        this.timestamp = timestamp;
        this.description = description;
    }

    public Integer getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(Integer snapshotId) {
        this.snapshotId = snapshotId;
    }

    public Integer getContextId() {
        return contextId;
    }

    public void setContextId(Integer contextId) {
        this.contextId = contextId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getVariables() {
        return variables;
    }

    public void setVariables(String variables) {
        this.variables = variables;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
