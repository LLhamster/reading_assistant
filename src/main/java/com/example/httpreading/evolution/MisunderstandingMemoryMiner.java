package com.example.httpreading.evolution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.example.httpreading.memory.model.MemoryItem;
import com.example.httpreading.service.AgentMemoryService;
import com.example.httpreading.service.ModelClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MisunderstandingMemoryMiner {
    private static final Logger log = LoggerFactory.getLogger(MisunderstandingMemoryMiner.class);
    private static final Map<String, FailureType> KEYWORDS = keywordMap();

    private final AgentMemoryService memoryService;
    private final ModelClient modelClient;
    private final ObjectMapper objectMapper;

    public MisunderstandingMemoryMiner(AgentMemoryService memoryService,
                                       ModelClient modelClient,
                                       ObjectMapper objectMapper) {
        this.memoryService = memoryService;
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
    }

    public List<MisunderstandingSignal> mine(String userId, int limit) {
        List<MemoryItem> memories = memoryService.recentImportantEpisodic(
            userId, limit <= 0 ? 50 : limit, 0.0);
        if (memories.isEmpty()) {
            return List.of();
        }
        Map<String, MisunderstandingSignal> signals = new LinkedHashMap<>();
        try {
            for (MisunderstandingSignal signal : parseModelSignals(modelClient.chat(prompt(memories)), memories)) {
                signals.put(key(signal), signal);
            }
        } catch (Exception exception) {
            log.warn("Self-Evolution 误解信号 LLM 解析失败，使用关键词兜底: {}", exception.getMessage());
        }
        for (MisunderstandingSignal signal : keywordFallback(memories)) {
            signals.putIfAbsent(key(signal), signal);
        }
        return List.copyOf(signals.values());
    }

    private List<MisunderstandingSignal> parseModelSignals(String raw, List<MemoryItem> memories) throws Exception {
        JsonNode root = objectMapper.readTree(extractJson(raw));
        JsonNode array = root.isArray() ? root : root.path("signals");
        if (!array.isArray()) {
            throw new IllegalArgumentException("signals must be a JSON array");
        }
        Map<String, MemoryItem> byId = new LinkedHashMap<>();
        memories.forEach(memory -> byId.put(safe(memory.getId()), memory));
        List<MisunderstandingSignal> result = new ArrayList<>();
        for (JsonNode node : array) {
            MemoryItem memory = byId.get(node.path("memoryId").asText(""));
            if (memory == null) {
                continue;
            }
            FailureType type = parseFailureType(node.path("failureType").asText(""));
            result.add(signal(memory, type, node.path("confidence").asDouble(0.75)));
        }
        return result;
    }

    private List<MisunderstandingSignal> keywordFallback(List<MemoryItem> memories) {
        List<MisunderstandingSignal> result = new ArrayList<>();
        for (MemoryItem memory : memories) {
            String content = safe(memory.getContent()).toLowerCase(Locale.ROOT);
            for (Map.Entry<String, FailureType> entry : KEYWORDS.entrySet()) {
                if (content.contains(entry.getKey())) {
                    result.add(signal(memory, entry.getValue(), 0.65));
                }
            }
        }
        return result;
    }

    private MisunderstandingSignal signal(MemoryItem memory, FailureType type, double confidence) {
        Map<String, Object> metadata = memory.getMetadata() == null ? Map.of() : memory.getMetadata();
        return new MisunderstandingSignal(
            "signal-" + UUID.nameUUIDFromBytes((safe(memory.getId()) + type).getBytes()),
            memory.getId(),
            memory.getContent(),
            type,
            confidence,
            longValue(metadata.get("bookId")),
            intValue(metadata.get("chapterIndex")),
            metadata);
    }

    private String prompt(List<MemoryItem> memories) throws Exception {
        List<Map<String, Object>> input = memories.stream().map(memory -> Map.<String, Object>of(
            "memoryId", safe(memory.getId()),
            "content", safe(memory.getContent()),
            "metadata", memory.getMetadata() == null ? Map.of() : memory.getMetadata())).toList();
        return """
            你是阅读助手的失败信号分析器。请从用户阅读问答记忆中识别用户对回答产生误解或不满的信号。
            重点识别及其语义等价表达：不理解、太概念、太简单、重复、没有例子、不要讲概念、
            直接举例、真实故事、完整讲出来、不是我要的、答非所问。

            failureType 只能是：
            TOO_CONCEPTUAL, TOO_SIMPLE, REPETITIVE, MISSING_EXAMPLE,
            MISSING_STORY_DETAIL, NOT_DIRECT, OFF_TOPIC。

            只输出 JSON，不要 Markdown：
            {"signals":[{"memoryId":"原 memoryId","failureType":"枚举","confidence":0.0}]}
            没有信号时输出 {"signals":[]}。不要把助手自己的普通解释误判成用户反馈。

            memories:
            %s
            """.formatted(objectMapper.writeValueAsString(input));
    }

    private FailureType parseFailureType(String value) {
        try {
            return FailureType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return FailureType.NOT_DIRECT;
        }
    }

    private String extractJson(String raw) {
        String text = safe(raw);
        int objectStart = text.indexOf('{');
        int arrayStart = text.indexOf('[');
        int start = objectStart < 0 ? arrayStart
            : arrayStart < 0 ? objectStart : Math.min(objectStart, arrayStart);
        int end = Math.max(text.lastIndexOf('}'), text.lastIndexOf(']'));
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("model output is not JSON");
        }
        return text.substring(start, end + 1);
    }

    private static Map<String, FailureType> keywordMap() {
        Map<String, FailureType> values = new LinkedHashMap<>();
        values.put("不理解", FailureType.TOO_CONCEPTUAL);
        values.put("太概念", FailureType.TOO_CONCEPTUAL);
        values.put("不要讲概念", FailureType.TOO_CONCEPTUAL);
        values.put("太简单", FailureType.TOO_SIMPLE);
        values.put("重复", FailureType.REPETITIVE);
        values.put("没有例子", FailureType.MISSING_EXAMPLE);
        values.put("直接举例", FailureType.MISSING_EXAMPLE);
        values.put("真实故事", FailureType.MISSING_STORY_DETAIL);
        values.put("完整讲出来", FailureType.MISSING_STORY_DETAIL);
        values.put("不是我要的", FailureType.NOT_DIRECT);
        values.put("答非所问", FailureType.OFF_TOPIC);
        return Map.copyOf(values);
    }

    private String key(MisunderstandingSignal signal) {
        return signal.memoryId() + ":" + signal.failureType();
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? null : Long.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? null : Integer.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
