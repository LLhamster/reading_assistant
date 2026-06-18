package com.example.httpreading.service.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.mcp.ExternalMcpCallResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class EvidenceAggregator {
    private static final int MAX_EXTERNAL_RESULT_CHARS = 6000;
    private static final double MIN_RELEVANCE = 0.05d;

    private final ObjectMapper objectMapper;

    public EvidenceAggregator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CollectedEvidence aggregate(AiChatRequest request,
                                       ChatPlan plan,
                                       ToolExecutionResult executionResult) {
        List<EvidenceItem> items = new ArrayList<>();
        List<String> externalRefs = new ArrayList<>();
        List<String> planRefs = executionResult == null ? List.of() : executionResult.planRefs();
        if (executionResult != null) {
            for (ExternalMcpCallResult result : executionResult.results()) {
                if (!result.isOk()) {
                    externalRefs.add(result.ref());
                    continue;
                }
                externalRefs.add(result.ref());
                items.addAll(itemsFromResult(request, plan, result));
            }
        }

        List<EvidenceItem> normalized = normalize(items);
        return new CollectedEvidence(
            normalized,
            sources(normalized),
            memoryRefs(normalized),
            externalRefs,
            planRefs,
            formattedEvidence(normalized));
    }

    private List<EvidenceItem> itemsFromResult(AiChatRequest request, ChatPlan plan, ExternalMcpCallResult result) {
        String tool = result.getToolName();
        Map<String, Object> root = parseObject(result.getContent());
        Object data = root.get("data");
        if ("context.get_recent_dialogue".equals(tool) || "context_get_recent_dialogue".equals(tool)) {
            String dialogue = stringFromMap(data, "dialogue");
            return dialogue.isBlank() ? List.of() : List.of(new EvidenceItem(
                "context:recent_dialogue",
                "recent_dialogue",
                "最近对话",
                dialogue,
                40,
                0.75d,
                Map.of()));
        }
        if ("context.get_current_page".equals(tool) || "context_get_current_page".equals(tool)) {
            String content = stringFromMap(data, "content");
            String chapterTitle = stringFromMap(data, "chapterTitle");
            return content.isBlank() ? List.of() : List.of(new EvidenceItem(
                "context:current_page",
                "current_page",
                chapterTitle.isBlank() ? "当前阅读页面划词" : "当前阅读页面划词：" + chapterTitle,
                content,
                10,
                1.0d,
                Map.of("bookId", request.getBookId(), "chapterIndex", request.getChapterIndex())));
        }
        if ("rag.search".equals(tool) || "rag_retrieve".equals(tool)) {
            return ragItems(request, data);
        }
        if ("memory.search".equals(tool) || "memory_search".equals(tool)) {
            return memoryItems(data);
        }
        if ("profile.list_categories".equals(tool) || "profile_list_categories".equals(tool)) {
            return profileCategoryItems(data);
        }
        if ("profile.get_category_detail".equals(tool) || "profile_get_category_detail".equals(tool)) {
            return profileDetailItems(data);
        }
        if ("profile.search_relevant".equals(tool) || "profile_search_relevant".equals(tool)) {
            return profileSearchItems(data);
        }
        return externalItem(result, plan);
    }

    private List<EvidenceItem> ragItems(AiChatRequest request, Object data) {
        if (!(data instanceof List<?> list)) {
            return List.of();
        }
        List<EvidenceItem> items = new ArrayList<>();
        int index = 1;
        for (Object value : list) {
            Map<String, Object> chunk = objectMap(value);
            String content = stringValue(chunk, "content");
            if (content.isBlank()) {
                index++;
                continue;
            }
            Integer chapterIndex = intValue(chunk.get("chapterIndex"));
            boolean currentChapter = request != null
                && chapterIndex != null
                && chapterIndex.equals(request.getChapterIndex());
            String sourceRef = stringValue(chunk, "sourceRef");
            if (sourceRef.isBlank()) {
                sourceRef = "RAG 片段 " + index;
            }
            String id = stringValue(chunk, "id");
            if (id.isBlank()) {
                id = String.valueOf(index);
            }
            items.add(new EvidenceItem(
                "rag:" + id,
                currentChapter ? "rag_current_chapter" : "rag_other_chapter",
                sourceRef,
                content,
                currentChapter ? 20 : 30,
                0.85d,
                chunk));
            index++;
        }
        return items;
    }

    private List<EvidenceItem> memoryItems(Object data) {
        if (!(data instanceof List<?> list)) {
            return List.of();
        }
        List<EvidenceItem> items = new ArrayList<>();
        int index = 1;
        for (Object value : list) {
            Map<String, Object> memory = objectMap(value);
            String content = stringValue(memory, "content");
            if (content.isBlank()) {
                continue;
            }
            String memoryType = stringValue(memory, "memoryType");
            if (memoryType.isBlank()) {
                memoryType = "working";
            }
            double importance = doubleValue(memory.get("importance"), 0.5d);
            int priority = switch (memoryType) {
                case "working" -> 50;
                case "episodic" -> 60;
                default -> 65;
            };
            String id = stringValue(memory, "id");
            if (id.isBlank()) {
                id = String.valueOf(index);
            }
            items.add(new EvidenceItem(
                "memory:" + id,
                memoryType + "_memory",
                "[" + memoryType + "] " + truncate(content, 80),
                content,
                priority,
                Math.max(MIN_RELEVANCE, importance),
                memory));
            index++;
        }
        return items;
    }

    private List<EvidenceItem> profileCategoryItems(Object data) {
        if (!(data instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        return List.of(new EvidenceItem(
            "profile:categories",
            "profile_category",
            "用户画像类别",
            truncate(writeLoose(data), 1600),
            55,
            0.65d,
            Map.of("usage", "style_guidance")));
    }

    private List<EvidenceItem> profileDetailItems(Object data) {
        Map<String, Object> detail = objectMap(data);
        if (detail.isEmpty()) {
            return List.of();
        }
        Map<String, Object> metadata = new LinkedHashMap<>(detail);
        metadata.put("usage", "style_guidance");
        return List.of(new EvidenceItem(
            "profile:detail:" + normalize(writeLoose(detail)).hashCode(),
            "profile_detail",
            "用户画像详情",
            truncate(writeLoose(detail), 2200),
            55,
            0.75d,
            metadata));
    }

    private List<EvidenceItem> profileSearchItems(Object data) {
        Map<String, Object> root = objectMap(data);
        Object matchedValue = root.get("matched");
        boolean matched = matchedValue instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(matchedValue));
        if (!matched) {
            return List.of();
        }
        Object items = root.get("items");
        if (!(items instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<EvidenceItem> result = new ArrayList<>();
        int index = 1;
        for (Object item : list) {
            Map<String, Object> profile = objectMap(item);
            String summary = stringValue(profile, "summary");
            if (summary.isBlank()) {
                summary = writeLoose(profile);
            }
            Map<String, Object> metadata = new LinkedHashMap<>(profile);
            metadata.put("usage", "style_guidance");
            result.add(new EvidenceItem(
                "profile:search:" + index + ":" + normalize(summary).hashCode(),
                "profile_search_result",
                "相关用户画像",
                truncate(summary + "\n" + writeLoose(profile.get("detail")), 2200),
                55,
                doubleValue(profile.get("score"), 0.72d),
                metadata));
            index++;
        }
        return result;
    }

    private List<EvidenceItem> externalItem(ExternalMcpCallResult result, ChatPlan plan) {
        String content = truncate(result.getContent(), MAX_EXTERNAL_RESULT_CHARS);
        if (content.isBlank()) {
            return List.of();
        }
        double relevance = plan != null && (plan.taskType() == PlannerTaskType.GITHUB_ANALYSIS
            || plan.taskType() == PlannerTaskType.CODE_REPOSITORY_ANALYSIS
            || plan.taskType() == PlannerTaskType.FILE_STRUCTURE_EXPLORATION) ? 0.85d : 0.55d;
        return List.of(new EvidenceItem(
            "external:" + result.getServerName() + "/" + result.getToolName() + ":" + content.hashCode(),
            "external_mcp",
            result.getServerName() + "/" + result.getToolName(),
            content,
            70,
            relevance,
            Map.of("serverName", result.getServerName(), "toolName", result.getToolName())));
    }

    private List<EvidenceItem> normalize(List<EvidenceItem> items) {
        Map<String, EvidenceItem> byContent = new LinkedHashMap<>();
        for (EvidenceItem item : items) {
            String content = normalize(item.content());
            if (content.isBlank() || item.relevance() < MIN_RELEVANCE) {
                continue;
            }
            String key = content.length() <= 180 ? content : content.substring(0, 180);
            EvidenceItem existing = byContent.get(key);
            if (existing == null || compare(item, existing) < 0) {
                byContent.put(key, item);
            }
        }
        return byContent.values().stream()
            .sorted(this::compare)
            .limit(12)
            .toList();
    }

    private int compare(EvidenceItem left, EvidenceItem right) {
        int priority = Integer.compare(left.priority(), right.priority());
        if (priority != 0) {
            return priority;
        }
        return Double.compare(right.relevance(), left.relevance());
    }

    private List<String> sources(List<EvidenceItem> items) {
        Set<String> refs = new LinkedHashSet<>();
        for (EvidenceItem item : items) {
            if (item.type().startsWith("rag") || "current_page".equals(item.type())) {
                refs.add(item.source());
            }
        }
        return List.copyOf(refs);
    }

    private List<String> memoryRefs(List<EvidenceItem> items) {
        return items.stream()
            .filter(item -> item.type().endsWith("_memory"))
            .sorted(Comparator.comparingInt(EvidenceItem::priority))
            .limit(4)
            .map(EvidenceItem::source)
            .toList();
    }

    private String formattedEvidence(List<EvidenceItem> items) {
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("已收集证据：\n");
        int index = 1;
        for (EvidenceItem item : items) {
            builder.append("【证据").append(index).append("】")
                .append(item.source()).append("\n")
                .append("type=").append(item.type());
            Object usage = item.metadata() == null ? null : item.metadata().get("usage");
            if (usage != null) {
                builder.append(", usage=").append(usage);
            }
            builder.append("\n")
                .append(item.content()).append("\n\n");
            index++;
        }
        return builder.toString();
    }

    private String writeLoose(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return String.valueOf(value);
        }
    }

    private Map<String, Object> parseObject(String content) {
        if (content == null || content.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(content, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            return Map.of("data", content);
        }
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null && item != null) {
                map.put(String.valueOf(key), item);
            }
        });
        return map;
    }

    private String stringFromMap(Object data, String key) {
        Map<String, Object> map = objectMap(data);
        return stringValue(map, key);
    }

    private String stringValue(Map<String, Object> map, String key) {
        if (map == null) {
            return "";
        }
        Object value = map.get(key);
        return value == null ? "" : normalize(String.valueOf(value));
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private String truncate(String value, int maxChars) {
        String normalized = normalize(value);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "\n[该工具结果过长，后续内容已截断]";
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
