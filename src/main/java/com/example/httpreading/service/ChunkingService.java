package com.example.httpreading.service;

import com.example.httpreading.domain.document.ChunkDoc;
import com.example.httpreading.domain.entity.Chapters;
import com.example.httpreading.memory.rag.RagDocument;
import com.example.httpreading.memory.rag.RagDocumentChunk;
import com.example.httpreading.memory.rag.RagDocumentProcessor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 章节文本切分服务
 * 将长章节内容切分成约 500 字符的小片段（用于 RAG 向量检索）
 */
@Service
public class ChunkingService {

    private static final int CHUNK_SIZE = 500;    // 每个 chunk 最大字符数
    private static final int CHUNK_OVERLAP = 50; // 相邻 chunk 重叠字符数
    private final RagDocumentProcessor documentProcessor = new RagDocumentProcessor(CHUNK_SIZE, CHUNK_OVERLAP);

    /**
     * 将单个章节切分为多个 chunk
     *
     * @param chapter    章节实体
     * @param bookTitle  书籍名称（用于溯源）
     * @param embeddingService 向量服务（用于生成 vector）
     * @return chunk 列表
     */
    public List<ChunkDoc> chunkChapter(Chapters chapter, String bookTitle, EmbeddingService embeddingService) {
        List<ChunkDoc> chunks = new ArrayList<>();
        String content = chapter.getContent();

        if (content == null || content.isBlank()) {
            return chunks;
        }

        RagDocument document = new RagDocument(content, Map.of(
            "book_id", chapter.getBookId(),
            "chapter_index", chapter.getChapterIndex(),
            "book_title", bookTitle,
            "chapter_title", chapter.getTitle(),
            "volume_index", chapter.getVolumeIndex(),
            "volume_title", chapter.getVolumeTitle()
        ));

        List<RagDocumentChunk> documentChunks = documentProcessor.processDocument(document);
        for (RagDocumentChunk documentChunk : documentChunks) {
            String chunkText = documentChunk.getContent();
            int chunkIndex = documentChunk.getChunkIndex();

            // 生成 chunk doc（不包含 vector，vector 后续单独生成）
            ChunkDoc chunk = new ChunkDoc();
            chunk.setId(buildId(chapter.getBookId(), chapter.getChapterIndex(), chunkIndex));
            chunk.setBookId(chapter.getBookId());
            chunk.setChapterIndex(chapter.getChapterIndex());
            chunk.setChunkIndex(chunkIndex);
            chunk.setBookTitle(bookTitle);
            chunk.setChapterTitle(chapter.getTitle());
            chunk.setVolumeIndex(chapter.getVolumeIndex());
            chunk.setVolumeTitle(chapter.getVolumeTitle());
            chunk.setContent(chunkText);
            chunk.setVector(embeddingService.embed(chunkText)); // 调用 Kimi embedding

            chunks.add(chunk);
        }

        return chunks;
    }

    /**
     * 构建 chunk 的唯一 ID
     */
    public String buildId(Long bookId, Integer chapterIndex, Integer chunkIndex) {
        return bookId + "_" + chapterIndex + "_" + chunkIndex;
    }
}
