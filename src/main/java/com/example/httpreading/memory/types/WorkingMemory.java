package com.example.httpreading.memory.types;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import com.example.httpreading.memory.BaseMemory;
import com.example.httpreading.memory.MemoryConfig;
import com.example.httpreading.memory.model.MemoryItem;
import com.example.httpreading.tool.MetaData;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component
@Scope("prototype")
public class WorkingMemory extends BaseMemory {
    private final Integer maxCapacity;
    private final Integer memoryTokens;
    private final Integer memoryTtlMinutes;
    private Integer currentTokens;
    private final LocalDateTime sessionStart;
    private List<MemoryItem> memories;
    private PriorityQueue<MemoryItem> memoryHeap;

    public WorkingMemory(Map<String, Object> kwargs) {
        super(kwargs == null ? new HashMap<>() : kwargs);
        MemoryConfig config = kwargs != null && kwargs.get("memoryConfig") instanceof MemoryConfig c
            ? c
            : new MemoryConfig();
        this.maxCapacity = config.getWorkingMemoryCapacity() != null ? config.getWorkingMemoryCapacity() : 10;
        this.memoryTokens = config.getWorkingMemoryTokens() != null ? config.getWorkingMemoryTokens() : 2000;
        this.memoryTtlMinutes = config.getWorkingMemoryTtlMinutes() != null ? config.getWorkingMemoryTtlMinutes() : 120;
        this.currentTokens = 0;
        this.sessionStart = LocalDateTime.now();
        this.memories = new ArrayList<>();
        this.memoryHeap = newHeap();
    }

    @Override
    public synchronized String addMemory(MemoryItem memoryItem) {
        if (memoryItem == null || memoryItem.getContent() == null || memoryItem.getContent().isBlank()) {
            throw new IllegalArgumentException("memoryItem.content 不能为空");
        }
        if (memoryItem.getMetadata() == null) {
            memoryItem.setMetadata(new HashMap<>());
        }
        if (memoryItem.getTimestamp() == null) {
            memoryItem.setTimestamp(LocalDateTime.now());
        }
        if (memoryItem.getImportance() == null) {
            memoryItem.setImportance(0.5f);
        }

        expireOldMemories();
        memories.add(memoryItem);
        currentTokens += estimateTokens(memoryItem.getContent());
        rebuildHeap();
        enforceCapacityLimits();
        return memoryItem.getId();
    }

    @Override
    public synchronized List<MemoryItem> retrieve(String query, int limit, Map<String, Object> kwargs) {
        expireOldMemories();
        if (memories.isEmpty() || limit == 0) {
            return new ArrayList<>();
        }

        Map<String, Object> options = kwargs == null ? Map.of() : kwargs;
        String userId = readString(options, "userId", "user_id");
        String sessionId = readString(options, "sessionId", "session_id");
        float minImportance = readFloat(options, 0.0f, "minImportance", "min_importance", "importanceThreshold", "importance_threshold");

        List<ScoredMemory> scoredMemories = new ArrayList<>();
        for (MemoryItem memory : memories) {
            Map<String, Object> metadata = memory.getMetadata();
            if (Boolean.TRUE.equals(metadata == null ? null : metadata.get("forgotten"))) {
                continue;
            }
            if (userId != null && !userId.equals(memory.getUserId())) {
                continue;
            }
            if (sessionId != null && !sessionId.equals(readString(metadata, "session_id", "sessionId"))) {
                continue;
            }
            if (memory.getImportance() != null && memory.getImportance() < minImportance) {
                continue;
            }

            float finalScore = scoreMemory(query, memory);
            if (finalScore > 0 || query == null || query.isBlank()) {
                memory.getMetadata().put("relevance_score", finalScore);
                scoredMemories.add(new ScoredMemory(finalScore, memory));
            }
        }

        scoredMemories.sort(Comparator
            .comparingDouble((ScoredMemory item) -> item.score)
            .thenComparing(item -> item.memory.getTimestamp())
            .reversed());

        int actualLimit = limit <= 0 ? scoredMemories.size() : Math.min(limit, scoredMemories.size());
        List<MemoryItem> result = new ArrayList<>();
        for (int i = 0; i < actualLimit; i++) {
            result.add(scoredMemories.get(i).memory);
        }
        return result;
    }

    @Override
    public synchronized boolean update(String memoryId, String newContent, float importance, MetaData metaData) {
        for (MemoryItem memory : memories) {
            if (memory.getId().equals(memoryId)) {
                int oldTokens = estimateTokens(memory.getContent());
                if (newContent != null) {
                    memory.setContent(newContent);
                    currentTokens = currentTokens - oldTokens + estimateTokens(newContent);
                }
                memory.setImportance(Math.max(0.0f, Math.min(1.0f, importance)));
                if (metaData != null) {
                    memory.getMetadata().put("session_id", metaData.getSession_id());
                    memory.getMetadata().put("timestamp", metaData.getTimestamp());
                }
                memory.setTimestamp(LocalDateTime.now());
                rebuildHeap();
                enforceCapacityLimits();
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized boolean remove(String memoryId) {
        for (int i = 0; i < memories.size(); i++) {
            MemoryItem memory = memories.get(i);
            if (memory.getId().equals(memoryId)) {
                memories.remove(i);
                currentTokens = Math.max(0, currentTokens - estimateTokens(memory.getContent()));
                rebuildHeap();
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized boolean hasMemory(String memoryId) {
        if (memoryId == null) {
            return false;
        }
        return memories.stream().anyMatch(memory -> memoryId.equals(memory.getId()));
    }

    @Override
    public synchronized void clear() {
        memories.clear();
        memoryHeap.clear();
        currentTokens = 0;
    }

    @Override
    public synchronized List<MemoryItem> getAll() {
        expireOldMemories();
        return new ArrayList<>(memories);
    }

    @Override
    public synchronized Map<String, Object> getStats() {
        expireOldMemories();
        double avgImportance = memories.stream()
            .map(MemoryItem::getImportance)
            .filter(value -> value != null)
            .mapToDouble(Float::doubleValue)
            .average()
            .orElse(0.0d);

        Map<String, Object> stats = new HashMap<>();
        stats.put("memory_type", "working");
        stats.put("count", memories.size());
        stats.put("total_count", memories.size());
        stats.put("current_tokens", currentTokens);
        stats.put("max_capacity", maxCapacity);
        stats.put("max_tokens", memoryTokens);
        stats.put("ttl_minutes", memoryTtlMinutes);
        stats.put("avg_importance", avgImportance);
        stats.put("session_duration_minutes", Duration.between(sessionStart, LocalDateTime.now()).toMinutes());
        return stats;
    }

    public synchronized List<MemoryItem> getRecent(int limit) {
        expireOldMemories();
        return memories.stream()
            .sorted(Comparator.comparing(MemoryItem::getTimestamp).reversed())
            .limit(Math.max(0, limit))
            .toList();
    }

    public synchronized String getContextSummary(int maxLength) {
        expireOldMemories();
        StringBuilder summary = new StringBuilder();
        memories.stream()
            .sorted(Comparator
                .comparing(MemoryItem::getImportance, Comparator.nullsLast(Float::compareTo))
                .thenComparing(MemoryItem::getTimestamp)
                .reversed())
            .forEach(memory -> {
                if (summary.length() >= maxLength) {
                    return;
                }
                String content = memory.getContent() == null ? "" : memory.getContent();
                int remaining = maxLength - summary.length();
                if (content.length() > remaining) {
                    summary.append(content, 0, Math.max(0, remaining)).append("...");
                } else {
                    summary.append(content).append("\n");
                }
            });
        return summary.toString().trim();
    }

    public synchronized void expireOldMemories() {
        if (memories.isEmpty()) {
            return;
        }
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(memoryTtlMinutes);
        List<MemoryItem> keptMemories = new ArrayList<>();
        int removedTokenSum = 0;
        for (MemoryItem item : memories) {
            if (item.getTimestamp() == null || item.getTimestamp().isAfter(cutoffTime)) {
                keptMemories.add(item);
            } else {
                removedTokenSum += estimateTokens(item.getContent());
            }
        }
        if (keptMemories.size() == memories.size()) {
            return;
        }
        this.memories = keptMemories;
        this.currentTokens = Math.max(0, currentTokens - removedTokenSum);
        rebuildHeap();
    }

    public float calculatePriority(MemoryItem item) {
        float importance = item.getImportance() == null ? 0.5f : item.getImportance();
        return importance * calculateTimeDecay(item.getTimestamp());
    }

    public float calculateTimeDecay(LocalDateTime timestamp) {
        if (timestamp == null) {
            return 1.0f;
        }
        Duration timeDiff = Duration.between(timestamp, LocalDateTime.now());
        float hoursPassed = timeDiff.getSeconds() / 3600.0f;
        float decayFactor = (float) Math.pow(memoryConfig.getDecayFactor(), hoursPassed / 6.0f);
        return Math.max(0.1f, decayFactor);
    }

    public synchronized void enforceCapacityLimits() {
        while (memories.size() > maxCapacity) {
            removeLowestPriorityMemory();
        }
        while (currentTokens > memoryTokens && !memories.isEmpty()) {
            removeLowestPriorityMemory();
        }
    }

    public synchronized void removeLowestPriorityMemory() {
        MemoryItem lowestPriorityMemory = memories.stream()
            .min(Comparator.comparingDouble(this::calculatePriority))
            .orElse(null);
        if (lowestPriorityMemory != null) {
            remove(lowestPriorityMemory.getId());
        }
    }

    public void remove(MemoryItem memoryItem) {
        if (memoryItem != null) {
            remove(memoryItem.getId());
        }
    }

    private float scoreMemory(String query, MemoryItem memory) {
        String content = memory.getContent() == null ? "" : memory.getContent();
        if (query == null || query.isBlank()) {
            return calculatePriority(memory);
        }

        String queryLower = query.toLowerCase();
        String contentLower = content.toLowerCase();

        float semanticScore = calculateTokenSimilarity(queryLower, contentLower);
        float keywordScore = calculateKeywordScore(queryLower, contentLower);
        float charScore = calculateCharacterOverlap(queryLower, contentLower);
        float baseRelevance = Math.max(semanticScore * 0.7f + keywordScore * 0.3f, charScore * 0.6f);
        float timeDecay = calculateTimeDecay(memory.getTimestamp());
        float importance = memory.getImportance() == null ? 0.5f : memory.getImportance();
        float importanceWeight = 0.8f + (importance * 0.4f);
        return baseRelevance * timeDecay * importanceWeight;
    }

    private float calculateTokenSimilarity(String query, String content) {
        Set<String> querySet = tokenize(query);
        Set<String> contentSet = tokenize(content);
        if (querySet.isEmpty() || contentSet.isEmpty()) {
            return 0.0f;
        }
        Set<String> intersection = new HashSet<>(querySet);
        intersection.retainAll(contentSet);
        if (intersection.isEmpty()) {
            return 0.0f;
        }
        Set<String> union = new HashSet<>(querySet);
        union.addAll(contentSet);
        return (float) intersection.size() / union.size();
    }

    private float calculateKeywordScore(String query, String content) {
        if (query.isBlank() || content.isBlank()) {
            return 0.0f;
        }
        if (content.contains(query)) {
            return Math.min(1.0f, (float) query.length() / Math.max(1, content.length()));
        }
        Set<String> querySet = tokenize(query);
        Set<String> contentSet = tokenize(content);
        Set<String> intersection = new HashSet<>(querySet);
        intersection.retainAll(contentSet);
        return intersection.isEmpty() ? 0.0f : ((float) intersection.size() / querySet.size()) * 0.8f;
    }

    private float calculateCharacterOverlap(String query, String content) {
        Set<Integer> queryChars = codePoints(query);
        Set<Integer> contentChars = codePoints(content);
        if (queryChars.isEmpty() || contentChars.isEmpty()) {
            return 0.0f;
        }
        Set<Integer> intersection = new HashSet<>(queryChars);
        intersection.retainAll(contentChars);
        return (float) intersection.size() / queryChars.size();
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        for (String token : text.split("[\\s,，。.!！?？;；:：()（）\\[\\]{}<>《》\"']+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private Set<Integer> codePoints(String text) {
        Set<Integer> points = new HashSet<>();
        if (text == null) {
            return points;
        }
        text.codePoints()
            .filter(ch -> !Character.isWhitespace(ch) && !Character.isISOControl(ch))
            .forEach(points::add);
        return points;
    }

    private String readString(Map<String, Object> options, String... keys) {
        if (options == null) {
            return null;
        }
        for (String key : keys) {
            Object value = options.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return null;
    }

    private float readFloat(Map<String, Object> options, float fallback, String... keys) {
        for (String key : keys) {
            Object value = options.get(key);
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
        }
        return fallback;
    }

    private int estimateTokens(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        long cjk = content.codePoints()
            .filter(ch -> (ch >= 0x4E00 && ch <= 0x9FFF) || (ch >= 0x3400 && ch <= 0x4DBF))
            .count();
        int words = tokenize(content).size();
        return Math.max(1, (int) cjk + words);
    }

    private PriorityQueue<MemoryItem> newHeap() {
        return new PriorityQueue<>(Comparator.comparingDouble(this::calculatePriority).reversed());
    }

    private void rebuildHeap() {
        memoryHeap = newHeap();
        memoryHeap.addAll(memories);
    }

    private static class ScoredMemory {
        private final float score;
        private final MemoryItem memory;

        private ScoredMemory(float score, MemoryItem memory) {
            this.score = score;
            this.memory = memory;
        }
    }
}
