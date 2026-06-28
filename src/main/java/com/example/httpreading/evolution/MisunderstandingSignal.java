package com.example.httpreading.evolution;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

public record MisunderstandingSignal(String id,
                                     String memoryId,
                                     String sourceText,
                                     FailureType failureType,
                                     double confidence,
                                     Long bookId,
                                     Integer chapterIndex,
                                     Map<String, Object> metadata) {
    public MisunderstandingSignal {
        id = safe(id);
        memoryId = safe(memoryId);
        sourceText = safe(sourceText);
        failureType = failureType == null ? FailureType.NOT_DIRECT : failureType;
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        metadata = metadata == null ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
