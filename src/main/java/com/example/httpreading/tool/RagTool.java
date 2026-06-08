package com.example.httpreading.tool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.httpreading.config.SpringContextHolder;
import com.example.httpreading.domain.document.ChunkDoc;
import com.example.httpreading.service.RagService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
/**
 * 提供完整的 RAG 能力：
 * 添加多格式文档（PDF、Office、图片、音频等）
 * 智能检索与召回
 * LLM 增强问答
 * 知识库管理
 */
@Component
@Scope("prototype")
public class RagTool extends tool {
    private String knowledgeBasePath;

    @Value("${vector-db.url:}")
    private String qdrantUrl;
    @Value("${vector-db.api-key:}")
    private String qdrantApiKey;

    private String collectionName;
    private String ragNamespace;
    private Map<String, Map<String, Object>> pipelines;

    public RagTool() {
        this(new HashMap<>());
    }

    public RagTool(Map<String, Object> config) {
        super("RagTool", "RAG工具 - 支持多格式文档检索增强生成，提供智能问答能力");
        this.knowledgeBasePath = (String) config.getOrDefault("knowledge_base_path", "./knowledge_base");
        this.collectionName = (String) config.getOrDefault("collection_name", "rag_knowledge_base");
        this.ragNamespace = (String) config.getOrDefault("rag_namespace", "default");
        this.pipelines = new HashMap<>();
    }

    @Override
    public String run(Map<String, Object> params) {
        String action = params == null ? "search" : String.valueOf(params.getOrDefault("action", "search"));
        if ("ask".equals(action)) {
            return ask(params);
        }
        if ("search".equals(action)) {
            return search(params);
        }
        if ("stats".equals(action)) {
            return "RAG namespace=" + ragNamespace + ", collection=" + collectionName + ", path=" + knowledgeBasePath;
        }
        return "Unknown RAG action: " + action;
    }

    private String ask(Map<String, Object> params) {
        RagService ragService = SpringContextHolder.getBean(RagService.class);
        String question = readString(params, "question", "query");
        int limit = readInt(params.get("limit"), readInt(params.get("top_k"), 5));
        Long bookId = readLong(params.get("bookId"));
        Integer chapterIndex = readInteger(params.get("chapterIndex"));
        return ragService.answer(bookId, chapterIndex, question, limit, null).getAnswer();
    }

    private String search(Map<String, Object> params) {
        RagService ragService = SpringContextHolder.getBean(RagService.class);
        String query = readString(params, "query", "question");
        int limit = readInt(params.get("limit"), readInt(params.get("top_k"), 5));
        Long bookId = readLong(params.get("bookId"));
        Integer chapterIndex = readInteger(params.get("chapterIndex"));
        List<ChunkDoc> chunks = ragService.retrieve(bookId, chapterIndex, query, limit);
        if (chunks.isEmpty()) {
            return "未找到相关内容";
        }
        StringBuilder builder = new StringBuilder("搜索结果：\n");
        for (int i = 0; i < chunks.size(); i++) {
            ChunkDoc chunk = chunks.get(i);
            String content = chunk.getContent() == null ? "" : chunk.getContent();
            if (content.length() > 200) {
                content = content.substring(0, 200) + "...";
            }
            builder.append(i + 1)
                .append(". ")
                .append(chunk.getSourceRef())
                .append("\n")
                .append(content)
                .append("\n");
        }
        return builder.toString();
    }

    private String readString(Map<String, Object> params, String... keys) {
        if (params == null) {
            return "";
        }
        for (String key : keys) {
            Object value = params.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return "";
    }

    private int readInt(Object value, int fallback) {
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

    private Long readLong(Object value) {
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

    private Integer readInteger(Object value) {
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
    
}
