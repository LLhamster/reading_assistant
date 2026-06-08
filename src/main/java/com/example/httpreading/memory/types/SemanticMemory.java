package com.example.httpreading.memory.types;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.example.httpreading.memory.BaseMemory;
import com.example.httpreading.memory.MemoryConfig;
import com.example.httpreading.memory.embedding.model.EmbeddingModel;
import com.example.httpreading.memory.embedding.provider.EmbeddingProvider;
import com.example.httpreading.memory.model.MemoryItem;
import com.example.httpreading.memory.storage.QdrantStore;
import com.example.httpreading.tool.MetaData;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * 语义记忆：以「概念/知识」为中心的长期记忆。
 *
 * Python 版 hello_agents 使用 Qdrant + Neo4j 做向量和图混合检索。这个 Java 版本先保留同样的检索形状：
 * 向量召回优先，Qdrant 不可用时回退到本地向量/关键词混合排序。
 */
@Component
@Scope("prototype")
public class SemanticMemory extends BaseMemory {
    private static final Logger log = LoggerFactory.getLogger(SemanticMemory.class);

    private final List<MemoryItem> semanticMemories = new ArrayList<>();
    private final Map<String, List<Double>> memoryEmbeddings = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> memoryConcepts = new ConcurrentHashMap<>();

    private EmbeddingModel embedder;

    @Autowired(required = false)
    private QdrantStore qdrantStore;

    public SemanticMemory(Map<String, Object> kwargs) {
        super(kwargs == null ? new HashMap<>() : kwargs);
    }

    @PostConstruct
    private void init() {
        try {
            this.embedder = EmbeddingProvider.getTextEmbedder();
            log.info("SemanticMemory embedding ready, dimension={}", embedder.getDimension());
        } catch (Exception exception) {
            this.embedder = null;
            log.warn("SemanticMemory embedding unavailable, fallback to keyword retrieval: {}", exception.getMessage());
        }
    }

    @Override
    public synchronized String addMemory(MemoryItem memoryItem) {
        if (memoryItem == null || memoryItem.getContent() == null || memoryItem.getContent().isBlank()) {
            throw new IllegalArgumentException("semantic memory content 不能为空");
        }
        if (memoryItem.getMetadata() == null) {
            memoryItem.setMetadata(new HashMap<>());
        }
        memoryItem.setMemoryType("semantic");
        if (memoryItem.getTimestamp() == null) {
            memoryItem.setTimestamp(LocalDateTime.now());
        }
        if (memoryItem.getImportance() == null) {
            memoryItem.setImportance(calculateImportance(memoryItem.getContent(), null));
        }

        Set<String> concepts = extractConcepts(memoryItem.getContent());
        memoryItem.getMetadata().put("concepts", new ArrayList<>(concepts));
        memoryItem.getMetadata().put("concept_count", concepts.size());

        semanticMemories.removeIf(existing -> existing.getId().equals(memoryItem.getId()));
        semanticMemories.add(memoryItem);
        memoryConcepts.put(memoryItem.getId(), concepts);

        List<Double> vector = encode(memoryItem.getContent());
        if (!vector.isEmpty()) {
            memoryEmbeddings.put(memoryItem.getId(), vector);
            persistVector(memoryItem, vector, concepts);
        }

        return memoryItem.getId();
    }

    @Override
    public synchronized List<MemoryItem> retrieve(String query, int limit, Map<String, Object> kwargs) {
        if (semanticMemories.isEmpty() || limit == 0) {
            return List.of();
        }

        Map<String, Object> options = kwargs == null ? Map.of() : kwargs;
        String userId = readString(options, "userId", "user_id");
        float minImportance = readFloat(options, 0.0f, "minImportance", "min_importance", "importanceThreshold", "importance_threshold");

        List<ScoredMemory> scored = new ArrayList<>();
        Map<String, Double> qdrantScores = searchQdrant(query, Math.max(limit * 3, 10), userId);
        List<Double> queryVector = encode(query == null ? "" : query);
        Set<String> queryConcepts = extractConcepts(query);

        for (MemoryItem memory : semanticMemories) {
            if (Boolean.TRUE.equals(memory.getMetadata().get("forgotten"))) {
                continue;
            }
            if (userId != null && !userId.equals(memory.getUserId())) {
                continue;
            }
            float importance = memory.getImportance() == null ? 0.5f : memory.getImportance();
            if (importance < minImportance) {
                continue;
            }

            double vectorScore = qdrantScores.getOrDefault(memory.getId(), 0.0d);
            if (vectorScore == 0.0d && !queryVector.isEmpty()) {
                vectorScore = cosineSimilarity(queryVector, memoryEmbeddings.getOrDefault(memory.getId(), List.of()));
            }
            double keywordScore = keywordScore(query, memory.getContent());
            double conceptScore = conceptOverlap(queryConcepts, memoryConcepts.getOrDefault(memory.getId(), Set.of()));
            double base = Math.max(vectorScore, keywordScore * 0.8d);
            double combined = (base * 0.75d + conceptScore * 0.25d) * (0.8d + importance * 0.4d);

            if (combined > 0 || query == null || query.isBlank()) {
                memory.getMetadata().put("relevance_score", combined);
                memory.getMetadata().put("vector_score", vectorScore);
                memory.getMetadata().put("concept_score", conceptScore);
                scored.add(new ScoredMemory(combined, memory));
            }
        }

        return scored.stream()
            .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed())
            .limit(limit <= 0 ? scored.size() : limit)
            .map(ScoredMemory::memory)
            .collect(Collectors.toList());
    }

    @Override
    public synchronized boolean update(String memoryId, String newContent, float importance, MetaData metaData) {
        MemoryItem existing = find(memoryId);
        if (existing == null) {
            return false;
        }
        if (newContent != null && !newContent.isBlank()) {
            existing.setContent(newContent);
        }
        existing.setImportance(Math.max(0.0f, Math.min(1.0f, importance)));
        existing.setTimestamp(LocalDateTime.now());
        if (metaData != null) {
            existing.getMetadata().put("session_id", metaData.getSession_id());
            existing.getMetadata().put("timestamp", metaData.getTimestamp());
        }
        addMemory(existing);
        return true;
    }

    @Override
    public synchronized boolean remove(String memoryId) {
        boolean removed = semanticMemories.removeIf(memory -> memory.getId().equals(memoryId));
        memoryEmbeddings.remove(memoryId);
        memoryConcepts.remove(memoryId);
        if (removed && qdrantStore != null) {
            try {
                qdrantStore.deleteMemories(List.of(memoryId));
            } catch (Exception exception) {
                log.debug("Qdrant semantic delete failed for {}: {}", memoryId, exception.getMessage());
            }
        }
        return removed;
    }

    @Override
    public synchronized boolean hasMemory(String memoryId) {
        return find(memoryId) != null;
    }

    @Override
    public synchronized void clear() {
        List<String> ids = semanticMemories.stream().map(MemoryItem::getId).toList();
        semanticMemories.clear();
        memoryEmbeddings.clear();
        memoryConcepts.clear();
        if (qdrantStore != null && !ids.isEmpty()) {
            try {
                qdrantStore.deleteMemories(ids);
            } catch (Exception exception) {
                log.debug("Qdrant semantic clear failed: {}", exception.getMessage());
            }
        }
    }

    @Override
    public synchronized List<MemoryItem> getAll() {
        return new ArrayList<>(semanticMemories);
    }

    @Override
    public synchronized Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("memory_type", "semantic");
        stats.put("count", semanticMemories.size());
        stats.put("embedding_count", memoryEmbeddings.size());
        stats.put("concept_count", memoryConcepts.values().stream().mapToInt(Set::size).sum());
        stats.put("qdrant_enabled", qdrantStore != null);
        return stats;
    }

    private void persistVector(MemoryItem memoryItem, List<Double> vector, Set<String> concepts) {
        if (qdrantStore == null || vector.size() != qdrantStore.getVectorSize()) {
            return;
        }

        Map<String, Object> payload = new HashMap<>(memoryItem.getMetadata());
        payload.put("memory_id", memoryItem.getId());
        payload.put("memory_type", "semantic");
        payload.put("user_id", memoryItem.getUserId());
        payload.put("content", memoryItem.getContent());
        payload.put("importance", memoryItem.getImportance());
        payload.put("timestamp", memoryItem.getTimestamp().atZone(ZoneId.systemDefault()).toEpochSecond());
        payload.put("concepts", new ArrayList<>(concepts));

        boolean ok = qdrantStore.addVectors(List.of(vector), List.of(payload), List.of(memoryItem.getId()));
        if (!ok) {
            log.debug("Qdrant semantic upsert failed for {}", memoryItem.getId());
        }
    }

    private Map<String, Double> searchQdrant(String query, int limit, String userId) {
        if (qdrantStore == null) {
            return Map.of();
        }
        List<Double> queryVector = encode(query == null ? "" : query);
        if (queryVector.isEmpty() || queryVector.size() != qdrantStore.getVectorSize()) {
            return Map.of();
        }

        Map<String, Object> where = new HashMap<>();
        where.put("memory_type", "semantic");
        if (userId != null) {
            where.put("user_id", userId);
        }

        Map<String, Double> scores = new HashMap<>();
        for (Map<String, Object> result : qdrantStore.searchSimilar(queryVector, limit, null, where)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = result.get("metadata") instanceof Map
                ? (Map<String, Object>) result.get("metadata")
                : Map.of();
            String memoryId = Objects.toString(metadata.getOrDefault("memory_id", result.get("id")), null);
            if (memoryId != null) {
                scores.put(memoryId, readDouble(result.get("score"), 0.0d));
            }
        }
        return scores;
    }

    private List<Double> encode(String text) {
        if (embedder == null || text == null || text.isBlank()) {
            return List.of();
        }
        try {
            Object encoded = embedder.encode(text);
            return normalizeVector(encoded);
        } catch (Exception exception) {
            log.debug("SemanticMemory embedding failed: {}", exception.getMessage());
            return List.of();
        }
    }

    private List<Double> normalizeVector(Object encoded) {
        if (encoded instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof List<?> nested) {
                return nested.stream()
                    .filter(Number.class::isInstance)
                    .map(Number.class::cast)
                    .map(Number::doubleValue)
                    .toList();
            }
            return list.stream()
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::doubleValue)
                .toList();
        }
        if (encoded instanceof double[] values) {
            List<Double> vector = new ArrayList<>(values.length);
            for (double value : values) {
                vector.add(value);
            }
            return vector;
        }
        return List.of();
    }

    private Set<String> extractConcepts(String text) {
        Set<String> concepts = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return concepts;
        }
        for (String token : text.toLowerCase().split("[\\s,，。.!！?？;；:：()（）\\[\\]{}<>《》\"']+")) {
            if (token.length() >= 2) {
                concepts.add(token);
            }
        }
        if (concepts.isEmpty()) {
            text.codePoints()
                .filter(ch -> Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN)
                .limit(20)
                .mapToObj(ch -> new String(Character.toChars(ch)))
                .forEach(concepts::add);
        }
        return concepts;
    }

    private double conceptOverlap(Set<String> queryConcepts, Set<String> memoryConcepts) {
        if (queryConcepts.isEmpty() || memoryConcepts.isEmpty()) {
            return 0.0d;
        }
        Set<String> intersection = new HashSet<>(queryConcepts);
        intersection.retainAll(memoryConcepts);
        return (double) intersection.size() / (double) queryConcepts.size();
    }

    private double keywordScore(String query, String content) {
        if (query == null || query.isBlank() || content == null || content.isBlank()) {
            return 0.0d;
        }
        String q = query.toLowerCase();
        String c = content.toLowerCase();
        if (c.contains(q)) {
            return Math.min(1.0d, (double) q.length() / Math.max(1, c.length()));
        }
        Set<String> qSet = extractConcepts(q);
        Set<String> cSet = extractConcepts(c);
        if (qSet.isEmpty() || cSet.isEmpty()) {
            return 0.0d;
        }
        Set<String> intersection = new HashSet<>(qSet);
        intersection.retainAll(cSet);
        return intersection.isEmpty() ? 0.0d : (double) intersection.size() / (double) qSet.size();
    }

    private double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0d;
        }
        int len = Math.min(a.size(), b.size());
        double dot = 0.0d;
        double normA = 0.0d;
        double normB = 0.0d;
        for (int i = 0; i < len; i++) {
            double av = a.get(i);
            double bv = b.get(i);
            dot += av * bv;
            normA += av * av;
            normB += bv * bv;
        }
        if (normA == 0.0d || normB == 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private MemoryItem find(String memoryId) {
        if (memoryId == null) {
            return null;
        }
        return semanticMemories.stream()
            .filter(memory -> memoryId.equals(memory.getId()))
            .findFirst()
            .orElse(null);
    }

    private String readString(Map<String, Object> options, String... keys) {
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

    private double readDouble(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(value.toString());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private record ScoredMemory(double score, MemoryItem memory) {
    }
}
