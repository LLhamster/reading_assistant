package com.example.httpreading.memory.model;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;


public class MemoryItem {
    private String id;
    private String content;
    private String memoryType;
    private String userId;
    private LocalDateTime timestamp;
    private Float importance;
    private Map<String, Object> metadata;

    public MemoryItem() {
        this.timestamp = LocalDateTime.now();
        this.importance = 0.5f;
        this.metadata = new HashMap<>();
    }

    public MemoryItem(String id, String content, String memoryType, String userId, LocalDateTime timestamp, Float importance, Map<String, Object> metadata) {
        this.id = id;
        this.content = content;
        this.memoryType = memoryType;
        this.userId = userId;
        this.timestamp = timestamp == null ? LocalDateTime.now() : timestamp;
        this.importance = importance == null ? 0.5f : importance;
        this.metadata = metadata == null ? new HashMap<>() : metadata;
    }
    //getter and setter
    public String getId() {
        return id;
    }
    public void setId(String id) {
        this.id = id;
    }
    public String getContent() {
        return content;
    }
    public void setContent(String content) {
        this.content = content;
    }
    public String getMemoryType() {
        return memoryType;
    }
    public void setMemoryType(String memoryType) {
        this.memoryType = memoryType;
    }
    public String getUserId() {
        return userId;
    }
    public void setUserId(String userId) {
        this.userId = userId;
    }
    public LocalDateTime getTimestamp() {
        return timestamp;
    }
    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
    public Float getImportance() {
        return importance;
    }
    public void setImportance(Float importance) {
        this.importance = importance;
    }
    public Map<String, Object> getMetadata() {
        return metadata;
    }
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
