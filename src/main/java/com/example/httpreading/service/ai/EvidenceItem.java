package com.example.httpreading.service.ai;

import java.util.Map;

public record EvidenceItem(String id,
                           String type,
                           String source,
                           String content,
                           int priority,
                           double relevance,
                           Map<String, Object> metadata) {
    public EvidenceItem {
        id = id == null ? "" : id;
        type = type == null ? "" : type;
        source = source == null ? "" : source;
        content = content == null ? "" : content;
        metadata = safeMetadata(metadata);
    }

    private static Map<String, Object> safeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> cleaned = new java.util.LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (key != null && value != null) {
                cleaned.put(key, value);
            }
        });
        return cleaned.isEmpty() ? Map.of() : Map.copyOf(cleaned);
    }
}
