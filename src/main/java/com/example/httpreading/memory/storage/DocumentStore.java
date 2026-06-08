package com.example.httpreading.memory.storage;

import java.util.List;
import java.util.Map;

public abstract class DocumentStore {
    public abstract String addMemory(String memoryId,
                                     String userId,
                                     String content,
                                     String memoryType,
                                     long timestamp,
                                     double importance,
                                     Map<String, Object> properties);

    public abstract Map<String, Object> getMemory(String memoryId);

    public abstract List<Map<String, Object>> searchMemory(String userId,
                                                           String memoryType,
                                                           Long startTime,
                                                           Long endTime,
                                                           Double importanceThreshold,
                                                           int limit);

    public boolean removeMemory(String memoryId) {
        return false;
    }
}
