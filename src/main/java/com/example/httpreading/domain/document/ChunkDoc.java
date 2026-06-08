package com.example.httpreading.domain.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.util.List;

/**
 * 书籍章节片段文档（用于 RAG 向量检索）
 * 每个 chunk = 一段约 500 字符的文本 + 对应的 1024 维向量
 */
@Document(indexName = "book_chunks")
public class ChunkDoc {

    @Id
    private String id; // 格式: bookId_chapterIndex_chunkIndex

    @Field(type = FieldType.Long)
    private Long bookId;

    @Field(type = FieldType.Integer)
    private Integer chapterIndex;

    @Field(type = FieldType.Integer)
    private Integer chunkIndex;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String bookTitle;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String chapterTitle;

    @Field(type = FieldType.Integer)
    private Integer volumeIndex;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String volumeTitle;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String content;

    // 1024 维向量（Kimi moonshot-text-embedding-v1 输出）
    private List<Float> vector;

    // ====== getter / setter ======

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }

    public Integer getChapterIndex() {
        return chapterIndex;
    }

    public void setChapterIndex(Integer chapterIndex) {
        this.chapterIndex = chapterIndex;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getBookTitle() {
        return bookTitle;
    }

    public void setBookTitle(String bookTitle) {
        this.bookTitle = bookTitle;
    }

    public String getChapterTitle() {
        return chapterTitle;
    }

    public void setChapterTitle(String chapterTitle) {
        this.chapterTitle = chapterTitle;
    }

    public Integer getVolumeIndex() {
        return volumeIndex;
    }

    public void setVolumeIndex(Integer volumeIndex) {
        this.volumeIndex = volumeIndex;
    }

    public String getVolumeTitle() {
        return volumeTitle;
    }

    public void setVolumeTitle(String volumeTitle) {
        this.volumeTitle = volumeTitle;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<Float> getVector() {
        return vector;
    }

    public void setVector(List<Float> vector) {
        this.vector = vector;
    }

    /** 生成引用来源描述 */
    public String getSourceRef() {
        String volume = volumeTitle == null || volumeTitle.isBlank() ? "" : volumeTitle + " / ";
        return "《" + bookTitle + "》" + volume + chapterTitle;
    }
}
