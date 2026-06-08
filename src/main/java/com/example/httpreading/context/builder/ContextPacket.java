package com.example.httpreading.context.builder;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public class ContextPacket {
    private String content;
    private LocalDateTime timestamp;
    private Map<String, Object> metadata;
    private int tokenCount;
    private double relevanceScore;

    public ContextPacket(String content, Map<String, Object> metadata) {
        this.content = content == null ? "" : content;
        this.timestamp = LocalDateTime.now();
        this.metadata = metadata == null ? new HashMap<>() : new HashMap<>(metadata);
        this.tokenCount = ContextTokenCounter.countTokens(this.content);
        this.relevanceScore = 0.0d;
    }

    public String getContent() {
        return content;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    public double getRelevanceScore() {
        return relevanceScore;
    }

    public void setRelevanceScore(double relevanceScore) {
        this.relevanceScore = relevanceScore;
    }

    public String type() {
        Object type = metadata.get("type");
        return type == null ? "context" : type.toString();
    }
}
