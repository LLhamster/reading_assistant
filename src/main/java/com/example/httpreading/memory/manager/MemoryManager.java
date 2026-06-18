package com.example.httpreading.memory.manager;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.httpreading.config.SpringContextHolder;
import com.example.httpreading.memory.BaseMemory;
import com.example.httpreading.memory.model.MemoryItem;
import com.example.httpreading.memory.types.EpisodicMemory;
import com.example.httpreading.memory.types.SemanticMemory;
import com.example.httpreading.memory.types.WorkingMemory;
import com.example.httpreading.memory.MemoryConfig;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
/**
config: Optional[MemoryConfig] = None,
        user_id: str = "default_user",
        enable_working: bool = True,
        enable_episodic: bool = True,
        enable_semantic: bool = True,
        enable_perceptual: bool = False
*/
@Component
@Scope("prototype")
public class MemoryManager {
    private String userId;
    private MemoryConfig memoryConfig;
    private boolean enableWorking = false;
    private boolean enableEpisodic = false;
    private boolean enableSemantic = false;
    private Map<String, BaseMemory> memoryTypes;
    // private boolean enablePerceptual;
    
    public MemoryManager(String userId,  MemoryConfig memoryConfig, boolean enableWorking, boolean enableEpisodic, boolean enableSemantic) {
        this.userId = userId;
        this.memoryConfig = memoryConfig;
        this.enableWorking = enableWorking;
        this.enableEpisodic = enableEpisodic;
        this.enableSemantic = enableSemantic;
        this.memoryTypes = new HashMap<>();
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("memoryConfig", memoryConfig);
        

        if(enableWorking) {
            memoryTypes.put("working", SpringContextHolder.getBean(WorkingMemory.class, kwargs));
        }
        if(enableEpisodic) {
            memoryTypes.put("episodic", SpringContextHolder.getBean(EpisodicMemory.class, kwargs));
        }
        if(enableSemantic) {
            memoryTypes.put("semantic", SpringContextHolder.getBean(SemanticMemory.class, kwargs));
        }
    }

    public String addMemory(String content, Map<String, Object> metaData, String memoryType, Float importance) {
        String resolvedType = resolveMemoryType(memoryType);
        if (resolvedType == null || "all".equals(resolvedType)) {
            resolvedType = classifyMemoryType(content, metaData);
        }
        if (!memoryTypes.containsKey(resolvedType)) {
            resolvedType = firstEnabledType();
        }
        if (resolvedType == null) {
            return "";
        }
        if (importance == null) {
            importance = calculateImportance(content, metaData);
        }

        MemoryItem memoryItem = new MemoryItem(
            UUID.randomUUID().toString(),
            content,
            resolvedType,
            userId,
            LocalDateTime.now(),
            importance,
            metaData
        );

        BaseMemory memory = memoryTypes.get(resolvedType);
        if (memory == null) {
            return "";
        }

        String storedId = memory.addMemory(memoryItem);
        return storedId == null || storedId.isBlank() ? memoryItem.getId() : storedId;
    }

    public List<MemoryItem> searchMemory(String query, int limit, Map<String, Object> kwargs, String memoryType) {
        String resolvedType = resolveMemoryType(memoryType);
        if (resolvedType == null) {
            return List.of();
        }

        if (!"all".equalsIgnoreCase(resolvedType)) {
            BaseMemory memory = memoryTypes.get(resolvedType);
            if (memory == null) {
                return List.of();
            }
            return memory.retrieve(query, limit, kwargs);
        }

        List<MemoryItem> merged = new ArrayList<>();
        for (BaseMemory memory : memoryTypes.values()) {
            merged.addAll(memory.retrieve(query, limit, kwargs));
        }

        boolean hasScore = merged.stream()
            .anyMatch(item -> item.getMetadata() != null && item.getMetadata().containsKey("relevance_score"));

        if (hasScore) {
            merged.sort(Comparator.comparingDouble(this::readRelevanceScore).reversed());
        }

        if (limit <= 0 || merged.size() <= limit) {
            return merged;
        }
        return merged.subList(0, limit);
    }

    public List<MemoryItem> recentImportant(String memoryType, int limit, double minImportance) {
        String resolvedType = resolveMemoryType(memoryType);
        if (resolvedType == null || "all".equalsIgnoreCase(resolvedType)) {
            resolvedType = "episodic";
        }
        BaseMemory memory = memoryTypes.get(resolvedType);
        if (memory == null) {
            return List.of();
        }
        int actualLimit = limit <= 0 ? 30 : limit;
        double threshold = Math.max(0d, Math.min(1d, minImportance));
        return memory.recentImportant(userId, actualLimit, threshold);
    }

    public Float calculateImportance(String content, Map<String, Object> metaData) {
        float importance = 0.5f;
        if (content != null && content.length() > 100) {
            importance += 0.1f;
        }
        List<String> keywords = List.of("重要", "关键", "必须", "注意", "警告", "错误");
        if (content != null) {
            for (String keyword : keywords) {
                if (content.contains(keyword)) {
                    importance += 0.2f;
                    break;
                }
            }
        }
        return Math.max(0.0f, Math.min(1.0f, importance));
    }

    public boolean updateMemory(String memoryId, String content, Float importance, Map<String, Object> metadata) {
        if (memoryId == null || memoryId.isBlank()) {
            return false;
        }
        float resolvedImportance = importance == null ? calculateImportance(content, metadata) : importance;
        com.example.httpreading.tool.MetaData metaData = null;
        if (metadata != null) {
            Object sessionId = metadata.get("session_id");
            Object timestamp = metadata.get("timestamp");
            metaData = new com.example.httpreading.tool.MetaData(
                sessionId == null ? null : sessionId.toString(),
                timestamp == null ? null : timestamp.toString());
        }
        for (BaseMemory memory : memoryTypes.values()) {
            if (memory.hasMemory(memoryId)) {
                return memory.update(memoryId, content, resolvedImportance, metaData);
            }
        }
        return false;
    }

    public boolean removeMemory(String memoryId) {
        for (BaseMemory memory : memoryTypes.values()) {
            if (memory.hasMemory(memoryId)) {
                return memory.remove(memoryId);
            }
        }
        return false;
    }

    public Map<String, Object> getMemoryStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("user_id", userId);
        stats.put("enabled_types", new ArrayList<>(memoryTypes.keySet()));
        int total = 0;
        Map<String, Object> byType = new HashMap<>();
        for (Map.Entry<String, BaseMemory> entry : memoryTypes.entrySet()) {
            Map<String, Object> typeStats = entry.getValue().getStats();
            byType.put(entry.getKey(), typeStats);
            Object count = typeStats.get("count");
            if (count instanceof Number number) {
                total += number.intValue();
            }
        }
        stats.put("total_memories", total);
        stats.put("memories_by_type", byType);
        return stats;
    }

    public void clearAllMemories() {
        for (BaseMemory memory : memoryTypes.values()) {
            memory.clear();
        }
    }

    private String resolveMemoryType(String memoryType) {
        if (memoryType == null || memoryType.isBlank()) {
            if (enableWorking) {
                return "working";
            }
            if (enableEpisodic) {
                return "episodic";
            }
            if (enableSemantic) {
                return "semantic";
            }
            return null;
        }

        String normalized = memoryType.trim().toLowerCase();
        if ("all".equals(normalized)) {
            return "all";
        }
        return normalized;
    }

    private String classifyMemoryType(String content, Map<String, Object> metadata) {
        if (metadata != null && metadata.get("type") != null) {
            return metadata.get("type").toString().toLowerCase();
        }
        if (content == null) {
            return firstEnabledType();
        }
        List<String> episodicKeywords = List.of("昨天", "今天", "明天", "上次", "记得", "发生", "经历", "刚才");
        for (String keyword : episodicKeywords) {
            if (content.contains(keyword)) {
                return "episodic";
            }
        }
        List<String> semanticKeywords = List.of("定义", "概念", "规则", "知识", "原理", "方法", "是什么", "为什么");
        for (String keyword : semanticKeywords) {
            if (content.contains(keyword)) {
                return "semantic";
            }
        }
        return firstEnabledType();
    }

    private String firstEnabledType() {
        if (memoryTypes.containsKey("working")) {
            return "working";
        }
        if (memoryTypes.containsKey("episodic")) {
            return "episodic";
        }
        if (memoryTypes.containsKey("semantic")) {
            return "semantic";
        }
        return null;
    }

    private double readRelevanceScore(MemoryItem item) {
        if (item.getMetadata() == null) {
            return 0d;
        }
        Object score = item.getMetadata().get("relevance_score");
        if (score instanceof Number number) {
            return number.doubleValue();
        }
        if (score instanceof String value) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException ignored) {
                return 0d;
            }
        }
        return 0d;
    }
}
