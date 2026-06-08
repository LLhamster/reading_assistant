package com.example.httpreading.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.httpreading.context.builder.ContextBuilder;
import com.example.httpreading.context.builder.ContextPacket;
import com.example.httpreading.context.manager.ContextManager;
import com.example.httpreading.context.model.Context;
import com.example.httpreading.domain.document.ChunkDoc;
import com.example.httpreading.memory.model.MemoryItem;
import com.example.httpreading.service.AgentMemoryService;
import com.example.httpreading.service.RagService;
import com.example.httpreading.service.ReadingContextCompactionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ReadingMcpToolService {
    private static final int DEFAULT_LIMIT = 5;
    private static final int DEFAULT_TOP_K = 3;

    private final AgentMemoryService agentMemoryService;
    private final RagService ragService;
    private final ContextManager contextManager;
    private final ContextBuilder contextBuilder;
    private final ReadingContextCompactionService readingContextCompactionService;
    private final ObjectMapper objectMapper;

    @Autowired
    public ReadingMcpToolService(AgentMemoryService agentMemoryService,
                                 RagService ragService,
                                 ContextManager contextManager,
                                 ContextBuilder contextBuilder,
                                 ReadingContextCompactionService readingContextCompactionService,
                                 ObjectMapper objectMapper) {
        this.agentMemoryService = agentMemoryService;
        this.ragService = ragService;
        this.contextManager = contextManager;
        this.contextBuilder = contextBuilder;
        this.readingContextCompactionService = readingContextCompactionService;
        this.objectMapper = objectMapper;
    }

    public ReadingMcpToolService(AgentMemoryService agentMemoryService,
                                 RagService ragService,
                                 ContextManager contextManager,
                                 ContextBuilder contextBuilder,
                                 ObjectMapper objectMapper) {
        this(agentMemoryService, ragService, contextManager, contextBuilder,
            new ReadingContextCompactionService(), objectMapper);
    }

    public String memorySearch(Map<String, Object> args) {
        String query = readString(args, "query");
        if (query.isBlank()) {
            return error("query 不能为空");
        }
        String userId = resolvedUserId(args);
        String sessionId = resolvedSessionId(args);
        int limit = positiveInt(args, "limit", DEFAULT_LIMIT);

        List<Map<String, Object>> data = agentMemoryService.search(userId, sessionId, query, limit)
            .stream()
            .map(this::memoryToMap)
            .toList();
        if (data.isEmpty()) {
            return success(data, "未找到相关内容");
        }
        return success(data);
    }

    public String memoryRememberTurn(Map<String, Object> args) {
        String question = readString(args, "question");
        String answer = readString(args, "answer");
        if (question.isBlank()) {
            return error("question 不能为空");
        }
        if (answer.isBlank()) {
            return error("answer 不能为空");
        }

        agentMemoryService.rememberTurn(
            resolvedUserId(args),
            resolvedSessionId(args),
            readLong(args, "bookId"),
            readInteger(args, "chapterIndex"),
            question,
            answer);
        return success(Map.of("remembered", true));
    }

    public String ragRetrieve(Map<String, Object> args) {
        String query = readFirstString(args, "query", "question");
        if (query.isBlank()) {
            return error("query 不能为空");
        }

        List<Map<String, Object>> data = ragService.retrieve(
                readLong(args, "bookId"),
                readInteger(args, "chapterIndex"),
                query,
                positiveInt(args, "topK", DEFAULT_TOP_K))
            .stream()
            .map(this::chunkToMap)
            .toList();
        if (data.isEmpty()) {
            return success(data, "未找到相关内容");
        }
        return success(data);
    }

    public String ragAnswer(Map<String, Object> args) {
        String question = readFirstString(args, "question", "query");
        if (question.isBlank()) {
            return error("question 不能为空");
        }

        RagService.RagAnswer answer = ragService.answer(
            readLong(args, "bookId"),
            readInteger(args, "chapterIndex"),
            question,
            positiveInt(args, "topK", DEFAULT_TOP_K),
            null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answer", answer.getAnswer());
        data.put("sources", answer.getSources().stream().map(this::chunkToMap).toList());
        data.put("sourceRefs", answer.getSourceRefs());
        return success(data);
    }

    public String contextBuild(Map<String, Object> args) {
        String question = readFirstString(args, "question", "query");
        if (question.isBlank()) {
            return error("question 不能为空");
        }

        String userId = resolvedUserId(args);
        String sessionId = resolvedSessionId(args);
        Context context = contextManager.getOrCreateContext(userId, sessionId);
        Integer contextId = context.getContextId();

        List<ContextPacket> packets = new ArrayList<>();
        String history = contextManager.renderHistory(contextId, 10);
        if (!history.isBlank()) {
            packets.add(new ContextPacket(history, Map.of("type", "history")));
        }

        Object rawPackets = args == null ? null : args.get("packets");
        if (rawPackets instanceof List<?> list) {
            for (Object item : list) {
                ContextPacket packet = toContextPacket(item);
                if (packet != null) {
                    packets.add(packet);
                }
            }
        }

        String prompt = contextBuilder.build(question, readString(args, "systemInstructions"), packets);
        contextManager.putVariable(contextId, "lastMcpQuestion", question, "mcp", 1.0d);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contextId", contextId);
        data.put("userId", userId);
        data.put("sessionId", sessionId);
        data.put("context", prompt);
        return success(data);
    }

    public String contextGetRecentDialogue(Map<String, Object> args) {
        String userId = resolvedUserId(args);
        String sessionId = resolvedSessionId(args);
        int limit = positiveInt(args, "limit", 5);
        Context context = contextManager.getOrCreateContext(userId, sessionId);
        String history = contextManager.renderHistory(context.getContextId(), limit);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contextId", context.getContextId());
        data.put("userId", userId);
        data.put("sessionId", sessionId);
        data.put("dialogue", history);
        return success(data, history.isBlank() ? "未找到最近对话" : "ok");
    }

    public String contextGetCurrentPage(Map<String, Object> args) {
        String content = readingContextCompactionService.selectedExcerpt(
            readLong(args, "bookId"),
            readInteger(args, "chapterIndex"),
            readString(args, "chapterTitle"),
            readString(args, "selectedText"),
            readString(args, "selectedContext"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bookId", readLong(args, "bookId"));
        data.put("chapterIndex", readInteger(args, "chapterIndex"));
        data.put("chapterTitle", readString(args, "chapterTitle"));
        data.put("content", content);
        data.put("hasSelection", !readString(args, "selectedText").isBlank()
            || !readString(args, "selectedContext").isBlank());
        return success(data, content.isBlank() ? "未提供当前页面上下文" : "ok");
    }

    private ContextPacket toContextPacket(Object item) {
        if (item instanceof String content && !content.isBlank()) {
            return new ContextPacket(content, Map.of("type", "context"));
        }
        if (!(item instanceof Map<?, ?> map)) {
            return null;
        }
        Object content = map.get("content");
        if (content == null || content.toString().isBlank()) {
            return null;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        Object metadataRaw = map.get("metadata");
        if (metadataRaw instanceof Map<?, ?> metadataMap) {
            for (Map.Entry<?, ?> entry : metadataMap.entrySet()) {
                if (entry.getKey() != null) {
                    metadata.put(entry.getKey().toString(), entry.getValue());
                }
            }
        }
        Object type = map.get("type");
        if (type != null && !type.toString().isBlank()) {
            metadata.put("type", type.toString());
        }
        return new ContextPacket(content.toString(), metadata);
    }

    private Map<String, Object> memoryToMap(MemoryItem memory) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", memory.getId());
        data.put("content", memory.getContent());
        data.put("memoryType", memory.getMemoryType());
        data.put("userId", memory.getUserId());
        data.put("importance", memory.getImportance());
        data.put("timestamp", memory.getTimestamp() == null ? null : memory.getTimestamp().toString());
        data.put("metadata", memory.getMetadata());
        return data;
    }

    private Map<String, Object> chunkToMap(ChunkDoc chunk) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", chunk.getId());
        data.put("bookId", chunk.getBookId());
        data.put("chapterIndex", chunk.getChapterIndex());
        data.put("chunkIndex", chunk.getChunkIndex());
        data.put("bookTitle", chunk.getBookTitle());
        data.put("chapterTitle", chunk.getChapterTitle());
        data.put("volumeIndex", chunk.getVolumeIndex());
        data.put("volumeTitle", chunk.getVolumeTitle());
        data.put("sourceRef", chunk.getSourceRef());
        data.put("content", chunk.getContent());
        return data;
    }

    private String success(Object data) {
        return writeJson(Map.of("ok", true, "data", data));
    }

    private String success(Object data, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("data", data);
        response.put("message", message);
        return writeJson(response);
    }

    private String error(String message) {
        return writeJson(Map.of("ok", false, "error", message));
    }

    private String writeJson(Map<String, Object> response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            return "{\"ok\":false,\"error\":\"JSON 序列化失败\"}";
        }
    }

    private String resolvedUserId(Map<String, Object> args) {
        String userId = readString(args, "userId");
        return userId.isBlank() ? "default_user" : userId;
    }

    private String resolvedSessionId(Map<String, Object> args) {
        String sessionId = readString(args, "sessionId");
        return sessionId.isBlank() ? "default_session" : sessionId;
    }

    private String readFirstString(Map<String, Object> args, String firstKey, String secondKey) {
        String first = readString(args, firstKey);
        return first.isBlank() ? readString(args, secondKey) : first;
    }

    private String readString(Map<String, Object> args, String key) {
        if (args == null || key == null) {
            return "";
        }
        Object value = args.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private int positiveInt(Map<String, Object> args, String key, int fallback) {
        Integer value = readInteger(args, key);
        return value == null || value <= 0 ? fallback : value;
    }

    private Integer readInteger(Map<String, Object> args, String key) {
        if (args == null || key == null) {
            return null;
        }
        Object value = args.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null && !value.toString().isBlank()) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Long readLong(Map<String, Object> args, String key) {
        if (args == null || key == null) {
            return null;
        }
        Object value = args.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null && !value.toString().isBlank()) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
