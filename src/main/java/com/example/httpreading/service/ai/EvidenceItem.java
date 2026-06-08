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
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
