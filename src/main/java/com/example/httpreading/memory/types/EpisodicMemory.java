package com.example.httpreading.memory.types;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.example.httpreading.memory.BaseMemory;
import com.example.httpreading.config.QdrantProperties;
import com.example.httpreading.memory.embedding.model.DashScopeEmbedding;
import com.example.httpreading.memory.embedding.model.EmbeddingModel;
import com.example.httpreading.memory.embedding.model.LocalTransformerEmbedding;
import com.example.httpreading.memory.embedding.model.TFIDFEmbedding;
import com.example.httpreading.memory.model.MemoryItem;
import com.example.httpreading.memory.storage.DocumentStore;
import com.example.httpreading.memory.storage.MySQLDocumentStore;
import com.example.httpreading.memory.storage.QdrantStore;
import com.example.httpreading.tool.MetaData;

import jakarta.annotation.PostConstruct;

/**
 * 情景记忆（Episodic Memory）
 * 存储具体事件和经历，包含时间、地点、上下文等信息
 */
@Component
@Scope("prototype")
public class EpisodicMemory extends BaseMemory {
    private static final Logger logger = LoggerFactory.getLogger(EpisodicMemory.class);

    // 本地缓存（内存）
    private final List<Episode> episodes;
    private final Map<String, List<String>> sessions;  // session_id -> episode_ids

    // 模式识别缓存
    private final Map<String, Object> patternsCache;
    private LocalDateTime lastPatternAnalysis;

    // 存储与模型组件
    private DocumentStore docStore;
    @Autowired(required = false)
    private QdrantStore qdrantStore;
    private EmbeddingModel embedder;

    // MySQL 配置
    @Value("${spring.datasource.url}")
    private String mysqlUrl;

    @Value("${spring.datasource.username}")
    private String mysqlUsername;

    @Value("${spring.datasource.password}")
    private String mysqlPassword;

    // Qdrant 配置（通过配置属性或 Spring Bean 注入）
    @Autowired(required = false)
    private QdrantProperties qdrantProperties;

    private String qdrantUrl;
    private String qdrantApiKey;
    private String qdrantCollection;
    private int qdrantVectorSize;
    private String qdrantDistance;
    private int qdrantTimeout;

    // Embedding 配置
    @Value("${model.embedding.type:dashscope}")
    private String embeddingType;

    @Value("${model.embedding.embeddingModel:text-embedding-v3}")
    private String embeddingModel;

    @Value("${model.embedding.apiKey:}")
    private String embeddingApiKey;

    @Value("${model.embedding.baseUrl:}")
    private String embeddingBaseUrl;

    /**
     * 构造函数
     * @param kwargs 包含配置信息的map
     */
    public EpisodicMemory(Map<String, Object> kwargs) {
        super(kwargs);
        this.episodes = new ArrayList<>();
        this.sessions = new HashMap<>();
        this.patternsCache = new HashMap<>();
        this.lastPatternAnalysis = null;
    }

    @PostConstruct
    @SuppressWarnings("unused")
    private void initFromConfig() {
        initializeDocumentStore();
        initializeEmbedder();
        initializeVectorStore();
    }

    /**
     * 初始化MySQL文档存储
     */
    private void initializeDocumentStore() {
        try {
            this.docStore = new MySQLDocumentStore(mysqlUrl, mysqlUsername, mysqlPassword);
        } catch (Exception e) {
            throw new IllegalStateException("初始化 MySQL 文档存储失败", e);
        }
    }

    /**
     * 初始化文本嵌入模型
     */
    private void initializeEmbedder() {
        try {
            String type = embeddingType == null ? "dashscope" : embeddingType.trim().toLowerCase();
            switch (type) {
                case "local":
                    this.embedder = new LocalTransformerEmbedding(embeddingModel);
                    break;
                case "tfidf":
                    this.embedder = new TFIDFEmbedding();
                    break;
                default:
                    this.embedder = new DashScopeEmbedding(embeddingModel, embeddingApiKey, embeddingBaseUrl);
                    break;
            }
        } catch (Exception e) {
            logger.warn("初始化 embedding 失败，回退到 TFIDF: {}", e.getMessage());
            this.embedder = new TFIDFEmbedding();
        }
    }

    /**
     * 初始化向量存储（Qdrant）
     */
    private void initializeVectorStore() {
        String collection = qdrantProperties == null ? null : qdrantProperties.getCollection();
        int vectorSize = qdrantProperties == null ? 0 : qdrantProperties.getVectorSize();
        String distance = qdrantProperties == null ? null : qdrantProperties.getDistance();
        int timeout = qdrantProperties == null ? 0 : qdrantProperties.getTimeout();

        this.qdrantUrl = qdrantProperties == null ? null : qdrantProperties.getUrl();
        this.qdrantApiKey = qdrantProperties == null ? null : qdrantProperties.getApiKey();
        this.qdrantCollection = collection == null || collection.isBlank() ? "hello_agents_vectors" : collection;
        this.qdrantVectorSize = vectorSize <= 0 ? 1024 : vectorSize;
        this.qdrantDistance = distance == null || distance.isBlank() ? "cosine" : distance;
        this.qdrantTimeout = timeout <= 0 ? 30 : timeout;

        int apiKeyLen = qdrantApiKey == null ? 0 : qdrantApiKey.length();
        logger.info("Qdrant config loaded: url={}, collection={}, vectorSize={}, distance={}, timeout={}s, apiKeyLen={}",
            qdrantUrl, qdrantCollection, qdrantVectorSize, qdrantDistance, qdrantTimeout, apiKeyLen);

        if (this.qdrantStore != null) {
            this.qdrantCollection = qdrantStore.getCollectionName();
            this.qdrantVectorSize = qdrantStore.getVectorSize();
            logger.info("Qdrant 向量存储使用 Spring Bean: collection={}", qdrantCollection);
            return;
        }

        logger.warn("未注入 QdrantStore，向量检索将回退到本地逻辑");
    }

    // ============ 必实现的抽象方法 ============

    @Override
    public String addMemory(MemoryItem memoryItem) {
        if (memoryItem == null) {
            throw new IllegalArgumentException("memoryItem 不能为空");
        }

        Map<String, Object> metadata = memoryItem.getMetadata() == null
            ? new HashMap<>()
            : new HashMap<>(memoryItem.getMetadata());
        String sessionId = Objects.toString(metadata.getOrDefault("session_id", "default_session"));
        @SuppressWarnings("unchecked")
        Map<String, Object> context = metadata.get("context") instanceof Map
            ? new HashMap<>((Map<String, Object>) metadata.get("context"))
            : new HashMap<>();
        String outcome = metadata.get("outcome") == null ? null : metadata.get("outcome").toString();

        float itemImportance = memoryItem.getImportance() == null ? 0.5f : memoryItem.getImportance().floatValue();

        Episode episode = new Episode(
            memoryItem.getId(),
            memoryItem.getUserId(),
            sessionId,
            memoryItem.getTimestamp(),
            memoryItem.getContent(),
            context,
            outcome,
            itemImportance);

        episodes.add(episode);
        sessions.computeIfAbsent(sessionId, key -> new ArrayList<>()).add(memoryItem.getId());

        long ts = memoryItem.getTimestamp() == null
            ? LocalDateTime.now().atZone(java.time.ZoneId.systemDefault()).toEpochSecond()
            : memoryItem.getTimestamp().atZone(java.time.ZoneId.systemDefault()).toEpochSecond();

        Map<String, Object> properties = new HashMap<>();
        properties.put("session_id", sessionId);
        properties.put("context", context);
        properties.put("outcome", outcome);
        properties.put("participants", metadata.getOrDefault("participants", List.of()));
        properties.put("tags", metadata.getOrDefault("tags", List.of()));
        properties.put("qdrant_collection", qdrantCollection);
        properties.put("content", memoryItem.getContent());
        properties.put("user_id", memoryItem.getUserId());
        properties.put("memory_id", memoryItem.getId());
        properties.put("memory_type", "episodic");
        properties.put("importance", itemImportance);
        properties.put("timestamp", ts);

        String storedId = docStore.addMemory(
            memoryItem.getId(),
            memoryItem.getUserId(),
            memoryItem.getContent(),
            "episodic",
            ts,
            (double) itemImportance,
            properties);

        persistToQdrant(memoryItem, sessionId, context, outcome, itemImportance, ts, properties);
        return storedId;
    }

    @Override
    public List<MemoryItem> retrieve(String query, int limit, Map<String, Object> kwargs) {
        String userId = readString(kwargs, "userId", "user_id");
        String sessionId = readString(kwargs, "sessionId", "session_id");
        Double importanceThreshold = readDouble(kwargs, "importanceThreshold", "importance_threshold");
        Long startTime = readEpochSeconds(kwargs, "startTime", "start_time");
        Long endTime = readEpochSeconds(kwargs, "endTime", "end_time");

        int candidateLimit = Math.max(limit * 20, 100);
        List<Map<String, Object>> docs = qdrantStore != null
            ? searchFromQdrant(query, candidateLimit, userId, sessionId, importanceThreshold)
            : docStore.searchMemory(
                userId,
                "episodic",
                startTime,
                endTime,
                importanceThreshold,
                candidateLimit);

        if (docs == null || docs.isEmpty()) {
            return new ArrayList<>();
        }

        List<Double> queryVec = toVector(query == null ? "" : query);
        long nowEpoch = LocalDateTime.now().atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
        List<ScoredItem> scored = new ArrayList<>();

        for (Map<String, Object> doc : docs) {
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = doc.get("properties") instanceof Map
                ? (Map<String, Object>) doc.get("properties")
                : new HashMap<>();

            if (sessionId != null && !sessionId.equals(Objects.toString(properties.get("session_id"), null))) {
            continue;
            }

            long ts = ((Number) doc.getOrDefault("timestamp", nowEpoch)).longValue();
            if (startTime != null && ts < startTime) {
                continue;
            }
            if (endTime != null && ts > endTime) {
                continue;
            }

            String content = Objects.toString(doc.get("content"), "");
            List<Double> contentVec = toVector(content);

            double vectorScore = cosineSimilarity(queryVec, contentVec);
            if (vectorScore <= 0d) {
            vectorScore = keywordScore(query, content);
            }

            double ageDays = Math.max(0d, (nowEpoch - ts) / 86400d);
            double recencyScore = 1d / (1d + ageDays);
            double importance = ((Number) doc.getOrDefault("importance", 0.5d)).doubleValue();
            double base = vectorScore * 0.8d + recencyScore * 0.2d;
            double finalScore = base * (0.8d + importance * 0.4d);

            LocalDateTime timestamp = LocalDateTime.ofInstant(Instant.ofEpochSecond(ts), ZoneId.systemDefault());
            Map<String, Object> metadata = new HashMap<>(properties);
            metadata.put("relevance_score", finalScore);
            metadata.put("vector_score", vectorScore);
            metadata.put("recency_score", recencyScore);

            MemoryItem item = new MemoryItem(
                Objects.toString(doc.get("memory_id"), ""),
                content,
                Objects.toString(doc.getOrDefault("memory_type", "episodic"), "episodic"),
                Objects.toString(doc.get("user_id"), ""),
                timestamp,
                (float) importance,
                metadata);
            scored.add(new ScoredItem(finalScore, item));
        }

        return scored.stream()
            .sorted(Comparator.comparingDouble(ScoredItem::score).reversed())
            .limit(limit)
            .map(ScoredItem::item)
            .collect(Collectors.toList());
    }

    @Override
    public boolean update(String memoryId, String newContent, float importance, MetaData metaData) {
        Episode episode = findEpisode(memoryId);
        if (episode == null) {
            return false;
        }
        if (newContent != null && !newContent.isBlank()) {
            episode.setContent(newContent);
        }
        episode.setImportance(Math.max(0.0f, Math.min(1.0f, importance)));
        episode.setTimestamp(LocalDateTime.now());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("session_id", metaData == null ? episode.getSessionId() : metaData.getSession_id());
        metadata.put("timestamp", metaData == null ? episode.getTimestamp().toString() : metaData.getTimestamp());
        metadata.put("context", episode.getContext());
        metadata.put("outcome", episode.getOutcome());

        MemoryItem item = new MemoryItem(
            episode.getId(),
            episode.getContent(),
            "episodic",
            episode.getUserId(),
            episode.getTimestamp(),
            episode.getImportance(),
            metadata);
        episodes.removeIf(existing -> memoryId.equals(existing.getId()));
        sessions.values().forEach(ids -> ids.remove(memoryId));
        addMemory(item);
        return true;
    }

    @Override
    public boolean remove(String memoryId) {
        Episode episode = findEpisode(memoryId);
        if (episode == null) {
            return false;
        }
        episodes.removeIf(item -> memoryId.equals(item.getId()));
        sessions.values().forEach(ids -> ids.remove(memoryId));
        if (qdrantStore != null) {
            try {
                qdrantStore.deleteMemories(List.of(memoryId));
            } catch (Exception exception) {
                logger.debug("删除 Qdrant 情景记忆失败: {}", exception.getMessage());
            }
        }
        return docStore == null || docStore.removeMemory(memoryId);
    }

    @Override
    public boolean hasMemory(String memoryId) {
        if (memoryId == null || memoryId.isBlank()) {
            return false;
        }
        if (findEpisode(memoryId) != null) {
            return true;
        }
        return docStore != null && docStore.getMemory(memoryId) != null;
    }

    @Override
    public void clear() {
        List<String> ids = episodes.stream().map(Episode::getId).toList();
        episodes.clear();
        sessions.clear();
        patternsCache.clear();
        if (qdrantStore != null && !ids.isEmpty()) {
            try {
                qdrantStore.deleteMemories(ids);
            } catch (Exception exception) {
                logger.debug("清空 Qdrant 情景记忆失败: {}", exception.getMessage());
            }
        }
    }

    @Override
    public List<MemoryItem> getAll() {
        return episodes.stream()
            .map(this::episodeToMemoryItem)
            .collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("memory_type", "episodic");
        stats.put("count", episodes.size());
        stats.put("session_count", sessions.size());
        stats.put("qdrant_enabled", qdrantStore != null);
        return stats;
    }

    private Episode findEpisode(String memoryId) {
        if (memoryId == null) {
            return null;
        }
        return episodes.stream()
            .filter(episode -> memoryId.equals(episode.getId()))
            .findFirst()
            .orElse(null);
    }

    private MemoryItem episodeToMemoryItem(Episode episode) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("session_id", episode.getSessionId());
        metadata.put("context", episode.getContext());
        metadata.put("outcome", episode.getOutcome());
        metadata.put("timestamp", episode.getTimestamp().toString());
        return new MemoryItem(
            episode.getId(),
            episode.getContent(),
            "episodic",
            episode.getUserId(),
            episode.getTimestamp(),
            episode.getImportance(),
            metadata);
    }

    private String readString(Map<String, Object> kwargs, String... keys) {
        if (kwargs == null) {
            return null;
        }
        for (String key : keys) {
            Object value = kwargs.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return null;
    }

    private Double readDouble(Map<String, Object> kwargs, String... keys) {
        if (kwargs == null) {
            return null;
        }
        for (String key : keys) {
            Object value = kwargs.get(key);
            if (value instanceof Number n) {
                return n.doubleValue();
            }
            if (value instanceof String s && !s.isBlank()) {
                try {
                    return Double.parseDouble(value.toString());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private Long readEpochSeconds(Map<String, Object> kwargs, String... keys) {
        if (kwargs == null) {
            return null;
        }
        for (String key : keys) {
            Object value = kwargs.get(key);
            if (value instanceof Number n) {
                return n.longValue();
            }
            if (value instanceof LocalDateTime ldt) {
                return ldt.atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
            }
        }
        return null;
    }

    private List<Double> toVector(String text) {
        if (embedder == null || text == null) {
            return List.of();
        }
        try {
            Object encoded = embedder.encode(text);
            if (encoded instanceof List<?> list && !list.isEmpty()) {
                if (list.get(0) instanceof List<?> first) {
                    return first.stream()
                            .filter(Number.class::isInstance)
                            .map(Number.class::cast)
                            .map(Number::doubleValue)
                            .collect(Collectors.toList());
                }
                return list.stream()
                        .filter(Number.class::isInstance)
                        .map(Number.class::cast)
                        .map(Number::doubleValue)
                        .collect(Collectors.toList());
            }
            if (encoded instanceof double[] arr) {
                List<Double> vec = new ArrayList<>(arr.length);
                for (double d : arr) vec.add(d);
                return vec;
            }
        } catch (Exception e) {
            logger.debug("Embedding 编码失败: {}", e.getMessage());
        }
        return List.of();
    }

    private void persistToQdrant(MemoryItem memoryItem,
                                 String sessionId,
                                 Map<String, Object> context,
                                 String outcome,
                                 float importance,
                                 long ts,
                                 Map<String, Object> baseProperties) {
        if (qdrantStore == null) {
            return;
        }

        List<Double> vector = toVector(memoryItem.getContent());
        if (vector.isEmpty()) {
            logger.debug("Qdrant 跳过写入：embedding 为空，memoryId={}", memoryItem.getId());
            return;
        }

        if (vector.size() != qdrantVectorSize) {
            logger.warn("Qdrant 向量维度不匹配，memoryId={}, expected={}, actual={}",
                memoryItem.getId(), qdrantVectorSize, vector.size());
            return;
        }

        Map<String, Object> payload = new HashMap<>(baseProperties);
        payload.put("session_id", sessionId);
        payload.put("context", context);
        payload.put("outcome", outcome);
        payload.put("content", memoryItem.getContent());
        payload.put("user_id", memoryItem.getUserId());
        payload.put("memory_id", memoryItem.getId());
        payload.put("memory_type", "episodic");
        payload.put("importance", importance);
        payload.put("timestamp", ts);

        boolean ok = qdrantStore.addVectors(
            List.of(vector),
            List.of(payload),
            List.of(memoryItem.getId()));

        if (!ok) {
            logger.warn("Qdrant 写入失败，memoryId={}", memoryItem.getId());
        }
    }

    private List<Map<String, Object>> searchFromQdrant(String query,
                                                       int candidateLimit,
                                                       String userId,
                                                       String sessionId,
                                                       Double importanceThreshold) {
        List<Double> queryVector = toVector(query == null ? "" : query);
        if (queryVector.isEmpty() || queryVector.size() != qdrantVectorSize) {
            logger.debug("Qdrant 查询向量不可用，回退本地检索");
            return docStore.searchMemory(
                userId,
                "episodic",
                null,
                null,
                importanceThreshold,
                candidateLimit);
        }

        Map<String, Object> where = new HashMap<>();
        where.put("memory_type", "episodic");
        if (userId != null) {
            where.put("user_id", userId);
        }
        if (sessionId != null) {
            where.put("session_id", sessionId);
        }

        List<Map<String, Object>> results = qdrantStore.searchSimilar(queryVector, candidateLimit, null, where);
        List<Map<String, Object>> docs = new ArrayList<>();

        for (Map<String, Object> result : results) {
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = result.get("metadata") instanceof Map
                ? new HashMap<>((Map<String, Object>) result.get("metadata"))
                : new HashMap<>();

            Map<String, Object> doc = new HashMap<>();
            doc.put("memory_id", metadata.getOrDefault("memory_id", result.get("id")));
            doc.put("user_id", metadata.getOrDefault("user_id", userId));
            doc.put("memory_type", metadata.getOrDefault("memory_type", "episodic"));
            doc.put("content", metadata.getOrDefault("content", ""));
            doc.put("timestamp", metadata.getOrDefault("timestamp", LocalDateTime.now().atZone(java.time.ZoneId.systemDefault()).toEpochSecond()));
            doc.put("importance", metadata.getOrDefault("importance", 0.5d));
            doc.put("properties", metadata);
            docs.add(doc);
        }

        return docs;
    }

    private double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0d;
        }
        int len = Math.min(a.size(), b.size());
        if (len == 0) {
            return 0d;
        }

        double dot = 0d;
        double na = 0d;
        double nb = 0d;
        for (int i = 0; i < len; i++) {
            double va = a.get(i);
            double vb = b.get(i);
            dot += va * vb;
            na += va * va;
            nb += vb * vb;
        }
        if (na == 0d || nb == 0d) {
            return 0d;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private double keywordScore(String query, String content) {
        if (query == null || query.isBlank() || content == null || content.isBlank()) {
            return 0d;
        }
        String q = query.toLowerCase();
        String c = content.toLowerCase();
        if (c.contains(q)) {
            return Math.min(1d, (double) q.length() / (double) c.length());
        }

        String[] qWords = q.split("\\s+");
        String[] cWords = c.split("\\s+");
        java.util.Set<String> qSet = java.util.Arrays.stream(qWords).filter(s -> !s.isBlank()).collect(Collectors.toSet());
        java.util.Set<String> cSet = java.util.Arrays.stream(cWords).filter(s -> !s.isBlank()).collect(Collectors.toSet());
        if (qSet.isEmpty() || cSet.isEmpty()) {
            return 0d;
        }
        java.util.Set<String> inter = new java.util.HashSet<>(qSet);
        inter.retainAll(cSet);
        if (inter.isEmpty()) {
            return 0d;
        }
        java.util.Set<String> union = new java.util.HashSet<>(qSet);
        union.addAll(cSet);
        return ((double) inter.size() / (double) union.size()) * 0.8d;
    }

    private record ScoredItem(double score, MemoryItem item) {}
}

/**
 * 情景记忆单元
 */
class Episode {
    private String id;
    private String userId;
    private String sessionId;
    private LocalDateTime timestamp;
    private String content;
    private float importance;
    private Map<String, Object> context;
    private String outcome;

    public Episode(String id,
                   String userId,
                   String sessionId,
                   LocalDateTime timestamp,
                   String content,
                   Map<String, Object> context,
                   String outcome,
                   float importance) {
        this.id = id;
        this.userId = userId;
        this.sessionId = sessionId;
        this.timestamp = timestamp == null ? LocalDateTime.now() : timestamp;
        this.content = content;
        this.context = context == null ? new HashMap<>() : context;
        this.outcome = outcome;
        this.importance = importance;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public float getImportance() {
        return importance;
    }

    public void setImportance(float importance) {
        this.importance = importance;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public void setContext(Map<String, Object> context) {
        this.context = context;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "Episode{" +
                "id='" + id + '\'' +
                ", content='" + content + '\'' +
                ", importance=" + importance +
                ", outcome='" + outcome + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
