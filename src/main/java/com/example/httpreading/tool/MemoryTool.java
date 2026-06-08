package com.example.httpreading.tool;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.example.httpreading.config.SpringContextHolder;
import com.example.httpreading.memory.MemoryConfig;
import com.example.httpreading.memory.manager.MemoryManager;

public class MemoryTool extends tool {
    private String userId = "default_user";
    private MemoryConfig memoryConfig;
    private List<String> memoryTypes;
    private String currentSessionId = null;
    private MemoryManager memoryManager;
    private int conversationCount = 0;

    public MemoryTool(String name, String description, String userId,
                        MemoryConfig memoryConfig, List<String> memoryTypes
    ) {
        super(name, description);
        this.userId = userId;
        this.memoryConfig = memoryConfig;
        this.memoryTypes = memoryTypes;
        this.memoryManager = SpringContextHolder.getBean(MemoryManager.class, userId,  memoryConfig, 
            memoryTypes.contains("working"), 
            memoryTypes.contains("episodic"), 
            memoryTypes.contains("semantic"));
    }

    /**
     * Runs the memory tool.
     *
     * @return A message indicating that the memory tool is running.
     */
    @Override
    public String run(Map<String, Object> params) {
        String action = (String) params.get("action");
        Map<String, Object> kwargs = params.entrySet()
            .stream()
            .filter(entry -> !entry.getKey().equals("action"))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return execute(action, kwargs);
    }

    public String execute(String action, Map<String, Object> kwargs) {
        switch (action) {
            case "add":
                return addMemory(kwargs);
            case "search":
                return searchMemory(kwargs);
            case "update":
                return updateMemory(kwargs);
            case "remove":
                return removeMemory(kwargs);
            case "stats":
                return memoryManager.getMemoryStats().toString();
            default:
                return "Unknown action: " + action;
        }
    }

    public String addMemory(Map<String, Object> kwargs) {
        if(currentSessionId == null) {
            currentSessionId = "session_" + LocalDateTime.now().toString();
        }
        Map<String, Object> metadata = kwargs.get("metadata") != null ? (Map<String, Object>) kwargs.get("metadata") : new HashMap<>();
        metadata.put("session_id", currentSessionId);
        metadata.put("timestamp", LocalDateTime.now().toString());
        Object content = kwargs.get("content");
        if (content == null || content.toString().isBlank()) {
            return "记忆内容不能为空";
        }
        String memoryType = kwargs.get("memoryType") == null ? "working" : kwargs.get("memoryType").toString();
        String memoryId = memoryManager.addMemory(content.toString(),
                                                    metadata, 
                                                    memoryType, 
                                                    readFloat(kwargs.get("importance")));
        if (memoryId == null || memoryId.isBlank()) {
            return "记忆添加失败：未启用对应记忆类型";
        }
        return "✅ 记忆已添加 (ID: " + memoryId.substring(0, 8) + "...)";
    }

    public String updateMemory(Map<String, Object> kwargs) {
        Object id = kwargs.get("memoryId");
        if (id == null) {
            id = kwargs.get("id");
        }
        if (id == null || id.toString().isBlank()) {
            return "memoryId 不能为空";
        }
        String content = kwargs.get("content") == null ? null : kwargs.get("content").toString();
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = kwargs.get("metadata") instanceof Map
            ? (Map<String, Object>) kwargs.get("metadata")
            : new HashMap<>();
        boolean ok = memoryManager.updateMemory(id.toString(), content, readFloat(kwargs.get("importance")), metadata);
        return ok ? "记忆已更新" : "未找到对应记忆";
    }

    public String removeMemory(Map<String, Object> kwargs) {
        Object id = kwargs.get("memoryId");
        if (id == null) {
            id = kwargs.get("id");
        }
        if (id == null || id.toString().isBlank()) {
            return "memoryId 不能为空";
        }
        boolean ok = memoryManager.removeMemory(id.toString());
        return ok ? "记忆已删除" : "未找到对应记忆";
    }

    public String searchMemory(Map<String, Object> kwargs) {
        String query = kwargs.get("query") != null ? kwargs.get("query").toString() : null;
        if (query == null || query.isBlank()) {
            query = kwargs.get("content") != null ? kwargs.get("content").toString() : "";
        }

        int limit = 5;
        Object limitRaw = kwargs.get("limit");
        if (limitRaw instanceof Number number) {
            limit = number.intValue();
        } else if (limitRaw != null) {
            try {
                limit = Integer.parseInt(limitRaw.toString());
            } catch (NumberFormatException ignored) {
                limit = 5;
            }
        }

        String memoryType = kwargs.get("memoryType") != null ? kwargs.get("memoryType").toString() : null;
        List<com.example.httpreading.memory.model.MemoryItem> results =
            memoryManager.searchMemory(query, limit, kwargs, memoryType);

        if (results.isEmpty()) {
            return "未找到相关记忆";
        }

        StringBuilder response = new StringBuilder();
        response.append("找到 ").append(results.size()).append(" 条记忆:\n");
        for (com.example.httpreading.memory.model.MemoryItem item : results) {
            String snippet = item.getContent() == null ? "" : item.getContent();
            if (snippet.length() > 60) {
                snippet = snippet.substring(0, 60) + "...";
            }
            Object score = item.getMetadata() == null ? null : item.getMetadata().get("relevance_score");
            response.append("- [")
                .append(item.getMemoryType())
                .append("] ")
                .append(snippet)
                .append(" (ID: ")
                .append(item.getId(), 0, Math.min(item.getId().length(), 8))
                .append("...")
                .append(score == null ? ")" : ", score=" + score + ")")
                .append("\n");
        }
        return response.toString();
    }

    private Float readFloat(Object value) {
        if (value instanceof Number number) {
            return number.floatValue();
        }
        if (value != null && !value.toString().isBlank()) {
            try {
                return Float.parseFloat(value.toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
    
}
