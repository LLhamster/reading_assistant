package com.example.httpreading.service;

import com.example.httpreading.domain.document.ChunkDoc;
import com.example.httpreading.domain.entity.Chapters;
import com.example.httpreading.repository.ChunkRepository;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 服务：问题向量化、召回阅读片段、构造增强提示词。
 */
@Service
public class RagService {
    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final String CHUNK_INDEX = "book_chunks";

    private final EmbeddingService embeddingService;
    private final ChunkRepository chunkRepository;
    private final RestTemplate restTemplate;
    private final ModelClient modelClient;
    private final ChunkingService chunkingService;
    private final DocumentStorageService documentStorageService;

    @Value("${spring.elasticsearch.uris:http://localhost:9200}")
    private String elasticsearchUrl;

    public RagService(EmbeddingService embeddingService,
                      ChunkRepository chunkRepository,
                      ModelClient modelClient,
                      ChunkingService chunkingService,
                      DocumentStorageService documentStorageService) {
        this.embeddingService = embeddingService;
        this.chunkRepository = chunkRepository;
        this.restTemplate = new RestTemplate();
        this.modelClient = modelClient;
        this.chunkingService = chunkingService;
        this.documentStorageService = documentStorageService;
    }

    public RagAnswer answer(String question, int topK) {
        return answer(null, null, question, topK, null);
    }

    public RagAnswer answer(Long bookId, Integer chapterIndex, String question, int topK, String additionalContext) {
        List<ChunkDoc> retrievedChunks = retrieve(bookId, chapterIndex, question, topK);
        if (retrievedChunks.isEmpty()) {
            return new RagAnswer("未找到相关内容片段，请尝试其他问题或联系管理员初始化索引。", List.of());
        }

        String prompt = buildPrompt(question, retrievedChunks, additionalContext);
        String answerText = modelClient.chat(prompt);
        return new RagAnswer(answerText, retrievedChunks);
    }

    public List<ChunkDoc> retrieve(Long bookId, Integer chapterIndex, String question, int topK) {
        if (question == null || question.isBlank()) {
            return List.of();
        }

        List<Float> questionVector = embeddingService.embed(question);
        if (questionVector == null || questionVector.isEmpty()) {
            log.warn("问题向量化失败，question={}", question);
            return List.of();
        }
        return knnSearch(questionVector, topK <= 0 ? 3 : topK, bookId, chapterIndex);
    }

    private List<ChunkDoc> knnSearch(List<Float> queryVector, int topK, Long bookId, Integer chapterIndex) {
        try {
            JSONObject script = new JSONObject();
            script.put("source", "cosineSimilarity(params.queryVector, 'vector') + 1.0");
            JSONArray queryVecArray = new JSONArray();
            for (Float value : queryVector) {
                queryVecArray.put(value);
            }
            script.put("params", new JSONObject().put("queryVector", queryVecArray));

            JSONObject scriptScore = new JSONObject();
            scriptScore.put("query", buildFilterQuery(bookId, chapterIndex));
            scriptScore.put("script", script);

            JSONObject body = new JSONObject();
            body.put("size", topK);
            body.put("query", new JSONObject().put("script_score", scriptScore));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(body.toString(), headers);

            ResponseEntity<String> response = restTemplate.postForEntity(
                normalizeEsUrl() + "/" + CHUNK_INDEX + "/_search",
                entity,
                String.class);

            JSONObject json = new JSONObject(response.getBody() == null ? "{}" : response.getBody());
            JSONArray hits = json.optJSONObject("hits") == null
                ? new JSONArray()
                : json.optJSONObject("hits").optJSONArray("hits");
            return parseHits(hits);
        } catch (Exception exception) {
            log.error("KNN 检索失败: {}", exception.getMessage(), exception);
            return List.of();
        }
    }

    private JSONObject buildFilterQuery(Long bookId, Integer chapterIndex) {
        JSONArray filter = new JSONArray();
        if (bookId != null) {
            filter.put(new JSONObject().put("term", new JSONObject().put("bookId", bookId)));
        }
        if (chapterIndex != null) {
            filter.put(new JSONObject().put("term", new JSONObject().put("chapterIndex", chapterIndex)));
        }
        if (filter.isEmpty()) {
            return new JSONObject().put("match_all", new JSONObject());
        }
        return new JSONObject().put("bool", new JSONObject().put("filter", filter));
    }

    private List<ChunkDoc> parseHits(JSONArray hits) {
        List<ChunkDoc> results = new ArrayList<>();
        if (hits == null) {
            return results;
        }
        for (int i = 0; i < hits.length(); i++) {
            JSONObject hit = hits.optJSONObject(i);
            JSONObject source = hit == null ? null : hit.optJSONObject("_source");
            if (source == null) {
                continue;
            }
            ChunkDoc chunk = new ChunkDoc();
            chunk.setId(source.optString("id"));
            chunk.setBookId(source.optLong("bookId"));
            chunk.setChapterIndex(source.optInt("chapterIndex"));
            chunk.setChunkIndex(source.optInt("chunkIndex"));
            chunk.setBookTitle(source.optString("bookTitle"));
            chunk.setChapterTitle(source.optString("chapterTitle"));
            if (source.has("volumeIndex") && !source.isNull("volumeIndex")) {
                chunk.setVolumeIndex(source.optInt("volumeIndex"));
            }
            chunk.setVolumeTitle(source.optString("volumeTitle", null));
            chunk.setContent(source.optString("content"));
            chunk.setVector(List.of());
            results.add(chunk);
        }
        return results;
    }

    String buildPrompt(String question, List<ChunkDoc> chunks) {
        return buildPrompt(question, chunks, null);
    }

    String buildPrompt(String question, List<ChunkDoc> chunks, String additionalContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个帮助用户理解技术书籍的助教。请严格基于给定上下文回答，不要编造未出现的信息。\n\n");

        if (additionalContext != null && !additionalContext.isBlank()) {
            sb.append("【会话上下文】\n").append(additionalContext).append("\n\n");
        }

        for (int i = 0; i < chunks.size(); i++) {
            ChunkDoc chunk = chunks.get(i);
            sb.append("【参考片段").append(i + 1).append("】\n");
            sb.append("来源：").append(chunk.getSourceRef()).append("\n");
            sb.append("内容：").append(chunk.getContent()).append("\n\n");
        }

        sb.append("【用户问题】").append(question).append("\n");
        sb.append("请用中文回答。如果问题与片段内容无关，请说明“未在当前阅读内容中找到相关信息”。");
        sb.append("最后列出答案依据的来源。");
        return sb.toString();
    }

    public int rebuildIndexForBook(Long bookId, List<Chapters> chapters, String bookTitle) {
        List<ChunkDoc> existingChunks = chunkRepository.findByBookId(bookId);
        chunkRepository.deleteAll(existingChunks);

        int totalChunks = 0;
        for (Chapters chapter : chapters) {
            if ((chapter.getContent() == null || chapter.getContent().isBlank())
                && chapter.getContentFilePath() != null
                && !chapter.getContentFilePath().isBlank()) {
                chapter.setContent(documentStorageService.readText(chapter.getContentFilePath()));
            }
            List<ChunkDoc> chunks = chunkingService.chunkChapter(chapter, bookTitle, embeddingService);
            chunkRepository.saveAll(chunks);
            totalChunks += chunks.size();
        }

        log.info("为书籍 {} ({}章) 构建 chunk 索引完成，共 {} 个 chunks",
            bookTitle, chapters.size(), totalChunks);
        return totalChunks;
    }

    private String normalizeEsUrl() {
        if (elasticsearchUrl == null || elasticsearchUrl.isBlank()) {
            return "http://localhost:9200";
        }
        return elasticsearchUrl.replaceAll("/$", "");
    }

    public static class RagAnswer {
        private final String answer;
        private final List<ChunkDoc> sources;

        public RagAnswer(String answer, List<ChunkDoc> sources) {
            this.answer = answer;
            this.sources = sources == null ? List.of() : sources;
        }

        public String getAnswer() {
            return answer;
        }

        public List<ChunkDoc> getSources() {
            return sources;
        }

        public List<String> getSourceRefs() {
            return sources.stream()
                .map(ChunkDoc::getSourceRef)
                .distinct()
                .collect(Collectors.toList());
        }
    }
}
