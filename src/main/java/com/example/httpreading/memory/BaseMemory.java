package com.example.httpreading.memory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.httpreading.memory.model.MemoryItem;

import com.example.httpreading.tool.MetaData;

public abstract class BaseMemory {
    protected String storage;
    protected MemoryConfig memoryConfig;
    protected String memoryType;

    public BaseMemory(Map<String, Object> kwargs) {
        this.storage = kwargs.get("storage") != null ? kwargs.get("storage").toString() : "in-memory";
        this.memoryConfig = kwargs.get("memoryConfig") != null ? (MemoryConfig) kwargs.get("memoryConfig") : new MemoryConfig(new HashMap<>());
        this.memoryType = kwargs.get("memoryType") != null ? kwargs.get("memoryType").toString() : "working";
    }


    public abstract String addMemory(MemoryItem memoryItem);

    public abstract List<MemoryItem> retrieve(String query, int limit, Map<String, Object> kwargs);

    public abstract boolean update(String memoryId, String newContent, float importance, MetaData metaData);

    public abstract boolean remove(String memoryId);

    public abstract boolean hasMemory(String memoryId);

    public void clear() {
    }

    public List<MemoryItem> getAll() {
        return List.of();
    }

    public List<MemoryItem> recentImportant(String userId, int limit, double minImportance) {
        return List.of();
    }

    public Map<String, Object> getStats() {
        return Map.of(
            "memory_type", memoryType,
            "count", 0
        );
    }

    public float calculateImportance(String content, MetaData metaData) {
        float importance = 0.5f; // Default importance
        if(content != null && content.length() > 100) {
            importance += 0.1f;
        }
        List<String> important_keywords = List.of("重要", "关键", "必须", "注意", "警告", "错误");
        if (content != null) {
            for(String keyword : important_keywords) {
                if(content.contains(keyword)) {
                    importance += 0.2f;
                    break;
                }
            }
        }
        return Math.max(0.0f, Math.min(1.0f, importance));
    }

    
}
