package com.example.httpreading.memory;

import java.util.Map;

/**
 *     # 统计显示用的基础配置（仅用于展示）
    max_capacity: int = 100
    importance_threshold: float = 0.1
    decay_factor: float = 0.95

    # 工作记忆特定配置
    working_memory_capacity: int = 10
    working_memory_tokens: int = 2000
    working_memory_ttl_minutes: int = 120
 */
public class MemoryConfig {
    private String storagePath;
    private Integer maxCapacity;
    private Float importanceThreshold;
    private Float decayFactor;
    private Integer workingMemoryCapacity;
    private Integer workingMemoryTokens;
    private Integer workingMemoryTtlMinutes;

    public MemoryConfig() {
        this(Map.of());
    }

    public MemoryConfig(Map<String, Object> config) {
        Map<String, Object> values = config == null ? Map.of() : config;
        this.storagePath = readString(values, "storage_path", "memory_storage.json");
        this.maxCapacity = readInt(values, "max_capacity", 100);
        this.importanceThreshold = readFloat(values, "importance_threshold", 0.1f);
        this.decayFactor = readFloat(values, "decay_factor", 0.95f);
        this.workingMemoryCapacity = readInt(values, "working_memory_capacity", 10);
        this.workingMemoryTokens = readInt(values, "working_memory_tokens", 2000);
        this.workingMemoryTtlMinutes = readInt(values, "working_memory_ttl_minutes", 120);
    }

    private String readString(Map<String, Object> config, String key, String fallback) {
        Object value = config.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private Integer readInt(Map<String, Object> config, String key, Integer fallback) {
        Object value = config.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null && !value.toString().isBlank()) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private Float readFloat(Map<String, Object> config, String key, Float fallback) {
        Object value = config.get(key);
        if (value instanceof Number number) {
            return number.floatValue();
        }
        if (value != null && !value.toString().isBlank()) {
            try {
                return Float.parseFloat(value.toString());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public Integer getMaxCapacity() {
        return maxCapacity;
    }

    public void setMaxCapacity(Integer maxCapacity) {
        this.maxCapacity = maxCapacity;
    }

    public Float getImportanceThreshold() {
        return importanceThreshold;
    }

    public void setImportanceThreshold(Float importanceThreshold) {
        this.importanceThreshold = importanceThreshold;
    }

    public Float getDecayFactor() {
        return decayFactor;
    }

    public void setDecayFactor(Float decayFactor) {
        this.decayFactor = decayFactor;
    }

    public Integer getWorkingMemoryCapacity() {
        return workingMemoryCapacity;
    }

    public void setWorkingMemoryCapacity(Integer workingMemoryCapacity) {
        this.workingMemoryCapacity = workingMemoryCapacity;
    }

    public Integer getWorkingMemoryTokens() {
        return workingMemoryTokens;
    }

    public void setWorkingMemoryTokens(Integer workingMemoryTokens) {
        this.workingMemoryTokens = workingMemoryTokens;
    }

    public Integer getWorkingMemoryTtlMinutes() {
        return workingMemoryTtlMinutes;
    }

    public void setWorkingMemoryTtlMinutes(Integer workingMemoryTtlMinutes) {
        this.workingMemoryTtlMinutes = workingMemoryTtlMinutes;
    }
    
}
