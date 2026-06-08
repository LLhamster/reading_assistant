package com.example.httpreading.context.manager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.httpreading.context.model.Context;
import com.example.httpreading.context.model.ContextMetadata;
import com.example.httpreading.context.model.ContextSnapshot;
import com.example.httpreading.context.model.ContextVariable;
import com.example.httpreading.context.model.ContextWindow;
import com.google.gson.Gson;
import org.springframework.stereotype.Service;

@Service
public class DefaultContextManager implements ContextManager {
    private static final Gson GSON = new Gson();

    private final Map<Integer, Context> contextStore = new ConcurrentHashMap<>();
    private final Map<String, Integer> sessionIndex = new ConcurrentHashMap<>();
    private final AtomicInteger contextIdSequence = new AtomicInteger(1000);
    private final AtomicInteger snapshotIdSequence = new AtomicInteger(1);

    @Override
    public Integer createContext() {
        return createContext("default_user", "default_session");
    }

    @Override
    public Integer createContext(String userId, String sessionId) {
        String resolvedUserId = normalize(userId, "default_user");
        String resolvedSessionId = normalize(sessionId, "default_session");
        String key = sessionKey(resolvedUserId, resolvedSessionId);

        Integer newContextId = contextIdSequence.incrementAndGet();
        Context context = new Context();
        context.setContextId(newContextId);
        context.setMetadata(new ConcurrentHashMap<>());
        context.setContextWindow(new ContextWindow());
        ContextMetadata metadata = new ContextMetadata();
        metadata.getTags().add("user:" + resolvedUserId);
        metadata.getTags().add("session:" + resolvedSessionId);
        context.setContextMetadata(metadata);
        context.setCreatedAt(LocalDateTime.now());
        context.setUpdatedAt(LocalDateTime.now());

        putVariable(context, "userId", resolvedUserId, "system", 1.0d);
        putVariable(context, "sessionId", resolvedSessionId, "system", 1.0d);
        contextStore.put(newContextId, context);
        sessionIndex.put(key, newContextId);
        return newContextId;
    }

    @Override
    public Context getOrCreateContext(String userId, String sessionId) {
        String resolvedUserId = normalize(userId, "default_user");
        String resolvedSessionId = normalize(sessionId, "default_session");
        Integer contextId = sessionIndex.get(sessionKey(resolvedUserId, resolvedSessionId));
        if (contextId != null) {
            Context context = contextStore.get(contextId);
            if (context != null) {
                return context;
            }
        }
        return contextStore.get(createContext(resolvedUserId, resolvedSessionId));
    }

    @Override
    public Context getContext(Integer contextId) {
        return contextStore.get(contextId);
    }

    @Override
    public void updateContext(Context context, Integer contextId) {
        if (context == null || contextId == null) {
            return;
        }
        context.setContextId(contextId);
        context.setUpdatedAt(LocalDateTime.now());
        contextStore.put(contextId, context);
    }

    @Override
    public void putVariable(Integer contextId, String key, Object value, String source, Double importance) {
        Context context = contextStore.get(contextId);
        if (context == null || key == null || key.isBlank()) {
            return;
        }
        putVariable(context, key, value, source, importance);
        context.setUpdatedAt(LocalDateTime.now());
    }

    @Override
    public void appendSnapshot(Integer contextId,
                               String userId,
                               String role,
                               String content,
                               Map<String, Object> metadata) {
        Context context = contextStore.get(contextId);
        if (context == null || content == null || content.isBlank()) {
            return;
        }

        ContextWindow window = context.getContextWindow();
        if (window.getHistory() == null) {
            window.setHistory(new java.util.LinkedList<>());
        }

        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("role", normalize(role, "user"));
        variables.put("content", content);
        variables.put("metadata", metadata == null ? Map.of() : metadata);

        ContextSnapshot snapshot = new ContextSnapshot(
            snapshotIdSequence.incrementAndGet(),
            contextId,
            normalize(userId, "default_user"),
            "v1",
            GSON.toJson(variables),
            Instant.now().getEpochSecond(),
            normalize(role, "message"));

        window.getHistory().add(snapshot);
        while (window.getMaxSize() != null && window.getHistory().size() > window.getMaxSize()) {
            window.getHistory().removeFirst();
        }
        window.setCurrentIndex(window.getHistory().size() - 1);
        context.setUpdatedAt(LocalDateTime.now());
    }

    @Override
    public List<ContextSnapshot> getRecentSnapshots(Integer contextId, int limit) {
        Context context = contextStore.get(contextId);
        if (context == null || context.getContextWindow() == null || context.getContextWindow().getHistory() == null) {
            return List.of();
        }
        List<ContextSnapshot> history = new ArrayList<>(context.getContextWindow().getHistory());
        int from = Math.max(0, history.size() - Math.max(0, limit));
        return history.subList(from, history.size());
    }

    @Override
    public Map<String, ContextVariable> getVariables(Integer contextId) {
        Context context = contextStore.get(contextId);
        if (context == null || context.getMetadata() == null) {
            return Map.of();
        }
        return new HashMap<>(context.getMetadata());
    }

    @Override
    public String renderHistory(Integer contextId, int limit) {
        List<ContextSnapshot> snapshots = getRecentSnapshots(contextId, limit);
        if (snapshots.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (ContextSnapshot snapshot : snapshots) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> variables = GSON.fromJson(snapshot.getVariables(), Map.class);
                String role = String.valueOf(variables.getOrDefault("role", "user"));
                String content = String.valueOf(variables.getOrDefault("content", ""));
                builder.append("[").append(role).append("] ").append(content).append("\n");
            } catch (Exception ignored) {
                builder.append(snapshot.getVariables()).append("\n");
            }
        }
        return builder.toString().trim();
    }

    @Override
    public void cleanupOldContexts(Long ttl) {
        long ttlSeconds = ttl == null || ttl <= 0 ? 7200L : ttl;
        LocalDateTime now = LocalDateTime.now();
        contextStore.entrySet().removeIf(entry -> {
            Context context = entry.getValue();
            boolean expired = context.getUpdatedAt().plusSeconds(ttlSeconds).isBefore(now);
            if (expired) {
                sessionIndex.values().removeIf(id -> id.equals(entry.getKey()));
            }
            return expired;
        });
    }

    private void putVariable(Context context, String key, Object value, String source, Double importance) {
        ContextVariable variable = new ContextVariable(
            key,
            value,
            inferType(value),
            importance == null ? 0.5d : Math.max(0.0d, Math.min(1.0d, importance)),
            normalize(source, "agent"),
            Instant.now().getEpochSecond());
        context.getMetadata().put(key, variable);
    }

    private String inferType(Object value) {
        if (value instanceof Number) {
            return "NUMBER";
        }
        if (value instanceof List<?>) {
            return "ARRAY";
        }
        if (value instanceof Map<?, ?>) {
            return "OBJECT";
        }
        if (value instanceof Boolean) {
            return "BOOLEAN";
        }
        return "STRING";
    }

    private String sessionKey(String userId, String sessionId) {
        return userId + ":" + sessionId;
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
