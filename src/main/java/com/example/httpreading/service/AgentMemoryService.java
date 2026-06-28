package com.example.httpreading.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.httpreading.memory.MemoryConfig;
import com.example.httpreading.memory.manager.MemoryManager;
import com.example.httpreading.memory.model.MemoryItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class AgentMemoryService {
    private static final Logger log = LoggerFactory.getLogger(AgentMemoryService.class);
    private static final int WORKING_QUESTION_CHARS = 180;
    private static final int WORKING_ANSWER_CHARS = 300;
    private static final int EPISODIC_QUESTION_CHARS = 160;
    private static final int EPISODIC_ANSWER_CHARS = 260;
    private static final int SEMANTIC_SUMMARY_CHARS = 520;

    private final ObjectProvider<MemoryManager> memoryManagerProvider;
    private final ModelClient modelClient;
    private final Map<String, MemoryManager> managers = new ConcurrentHashMap<>();

    public AgentMemoryService(ObjectProvider<MemoryManager> memoryManagerProvider, ModelClient modelClient) {
        this.memoryManagerProvider = memoryManagerProvider;
        this.modelClient = modelClient;
    }

    public List<MemoryItem> search(String userId, String sessionId, String query, int limit) {
        MemoryManager manager = getManager(userId);
        if (manager == null) {
            return List.of();
        }
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("userId", userId);

        List<MemoryItem> memories = new ArrayList<>();
        Map<String, Object> workingKwargs = new HashMap<>(kwargs);
        workingKwargs.put("session_id", sessionId);

        memories.addAll(manager.searchMemory(query, limit, workingKwargs, "working"));
        memories.addAll(manager.searchMemory(query, limit, kwargs, "episodic"));
        memories.addAll(manager.searchMemory(query, limit, kwargs, "semantic"));
        return memories;
    }

    public void rememberTurn(String userId,
                             String sessionId,
                             Long bookId,
                             Integer chapterIndex,
                             String question,
                             String answer) {
        rememberTurn(userId, sessionId, bookId, chapterIndex, question, answer, null);
    }

    public void rememberTurn(String userId,
                             String sessionId,
                             Long bookId,
                             Integer chapterIndex,
                             String question,
                             String answer,
                             Integer sourceCount) {
        MemoryManager manager = getManager(userId);
        if (manager == null) {
            return;
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("session_id", sessionId);
        metadata.put("bookId", bookId);
        metadata.put("chapterIndex", chapterIndex);
        metadata.put("type", "conversation");
        if (sourceCount != null) {
            metadata.put("sourceCount", sourceCount);
        }
        metadata.put("summary", true);
        metadata.put("raw_user_question", truncate(question, WORKING_QUESTION_CHARS));

        MemorySummary summary = summarizeTurn(bookId, chapterIndex, question, answer);

        manager.addMemory("用户提问：" + truncate(summary.question(), WORKING_QUESTION_CHARS), metadata, "working", 0.5f);
        manager.addMemory("助手回答：" + truncate(summary.conclusion(), WORKING_ANSWER_CHARS), metadata, "working", 0.6f);
        manager.addMemory(
            episodicSummary(bookId, chapterIndex, summary.question(), summary.conclusion(), question),
            metadata, "episodic", 0.7f);
    }

    public void rememberWorkingTurn(String userId,
                                    String sessionId,
                                    Long bookId,
                                    Integer chapterIndex,
                                    String question,
                                    String answer,
                                    Integer sourceCount) {
        MemoryManager manager = getManager(userId);
        if (manager == null) {
            return;
        }
        Map<String, Object> metadata = memoryMetadata(sessionId, bookId, chapterIndex, sourceCount);
        MemorySummary summary = summarizeTurn(bookId, chapterIndex, question, answer);
        manager.addMemory("用户提问：" + truncate(summary.question(), WORKING_QUESTION_CHARS), metadata, "working", 0.5f);
        manager.addMemory("助手回答：" + truncate(summary.conclusion(), WORKING_ANSWER_CHARS), metadata, "working", 0.6f);
    }

    public void rememberEpisodicTurn(String userId,
                                     String sessionId,
                                     Long bookId,
                                     Integer chapterIndex,
                                     String question,
                                     String answer,
                                     Integer sourceCount,
                                     float importance) {
        MemoryManager manager = getManager(userId);
        if (manager == null) {
            return;
        }
        Map<String, Object> metadata = memoryMetadata(sessionId, bookId, chapterIndex, sourceCount);
        metadata.put("raw_user_question", truncate(question, WORKING_QUESTION_CHARS));
        MemorySummary summary = summarizeTurn(bookId, chapterIndex, question, answer);
        manager.addMemory(
            episodicSummary(bookId, chapterIndex, summary.question(), summary.conclusion(), question),
            metadata,
            "episodic",
            Math.max(0.0f, Math.min(1.0f, importance)));
    }

    public Map<String, Object> stats(String userId) {
        MemoryManager manager = getManager(userId);
        return manager == null ? Map.of() : manager.getMemoryStats();
    }

    public List<MemoryItem> recentImportantEpisodic(String userId, int limit, double minImportance) {
        MemoryManager manager = getManager(userId);
        if (manager == null) {
            return List.of();
        }
        return manager.recentImportant("episodic", limit <= 0 ? 30 : limit, minImportance);
    }

    private MemoryManager getManager(String userId) {
        String resolvedUserId = userId == null || userId.isBlank() ? "default_user" : userId;
        return managers.computeIfAbsent(resolvedUserId, this::createManager);
    }

    private MemoryManager createManager(String userId) {
        MemoryConfig config = new MemoryConfig();
        try {
            return memoryManagerProvider.getObject(userId, config, true, true, true);
        } catch (Exception fullException) {
            log.warn("完整记忆管理器初始化失败，回退到 working memory: {}", fullException.getMessage());
            try {
                return memoryManagerProvider.getObject(userId, config, true, false, false);
            } catch (Exception workingException) {
                log.error("working memory 初始化失败: {}", workingException.getMessage(), workingException);
                return null;
            }
        }
    }

    private Map<String, Object> memoryMetadata(String sessionId,
                                               Long bookId,
                                               Integer chapterIndex,
                                               Integer sourceCount) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("session_id", sessionId);
        metadata.put("bookId", bookId);
        metadata.put("chapterIndex", chapterIndex);
        metadata.put("type", "conversation");
        if (sourceCount != null) {
            metadata.put("sourceCount", sourceCount);
        }
        metadata.put("summary", true);
        return metadata;
    }

    private String episodicSummary(Long bookId,
                                   Integer chapterIndex,
                                   String question,
                                   String answer,
                                   String rawQuestion) {
        return "阅读问答摘要：\n"
            + "问题：" + truncate(question, EPISODIC_QUESTION_CHARS) + "\n"
            + "用户原始提问：" + truncate(rawQuestion, WORKING_QUESTION_CHARS) + "\n"
            + "结论：" + truncate(answer, EPISODIC_ANSWER_CHARS) + "\n"
            + "位置：bookId=" + bookId + ", chapterIndex=" + chapterIndex;
    }

    private MemorySummary summarizeTurn(Long bookId, Integer chapterIndex, String question, String answer) {
        String fallbackQuestion = truncate(question, EPISODIC_QUESTION_CHARS);
        String fallbackConclusion = truncate(answer, EPISODIC_ANSWER_CHARS);
        try {
            String raw = modelClient.chat(memorySummaryPrompt(bookId, chapterIndex, question, answer));
            String normalized = normalizeSummary(raw);
            if (normalized.isBlank() || looksLikeModelFailure(normalized)) {
                return new MemorySummary(fallbackQuestion, fallbackConclusion);
            }
            return parseSummary(normalized, fallbackQuestion, fallbackConclusion);
        } catch (Exception exception) {
            log.warn("模型记忆摘要失败，回退到规则摘要: {}", exception.getMessage());
            return new MemorySummary(fallbackQuestion, fallbackConclusion);
        }
    }

    private String memorySummaryPrompt(Long bookId, Integer chapterIndex, String question, String answer) {
        return """
            请把一次阅读问答压缩成用于长期记忆检索的短语义摘要。
            要求：
            - 只保留用户真实关注点、助手核心结论、明确的书籍位置。
            - 不要保留寒暄、Markdown 装饰、长引用、工具日志。
            - 不要编造原文没有的信息。
            - 严格使用下面两行输出，不要输出其他内容：
            问题：...
            结论：...

            位置：bookId=%s, chapterIndex=%s
            用户问题：%s
            助手回答：%s
            """.formatted(
            bookId,
            chapterIndex,
            truncate(question, 900),
            truncate(answer, 1800));
    }

    private MemorySummary parseSummary(String raw, String fallbackQuestion, String fallbackConclusion) {
        String question = "";
        String conclusion = "";
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("问题：")) {
                question = trimmed.substring("问题：".length()).trim();
            } else if (trimmed.startsWith("结论：")) {
                conclusion = trimmed.substring("结论：".length()).trim();
            }
        }
        if (question.isBlank()) {
            question = fallbackQuestion;
        }
        if (conclusion.isBlank()) {
            conclusion = raw;
        }
        return new MemorySummary(
            truncate(question, EPISODIC_QUESTION_CHARS),
            truncate(conclusion, SEMANTIC_SUMMARY_CHARS));
    }

    private String normalizeSummary(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("```", "").trim();
    }

    private boolean looksLikeModelFailure(String value) {
        return value.startsWith("模型接口请求失败")
            || value.startsWith("调用模型接口异常")
            || value.startsWith("模型返回格式不符合预期");
    }

    private String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "...";
    }

    private record MemorySummary(String question, String conclusion) {
    }
}
