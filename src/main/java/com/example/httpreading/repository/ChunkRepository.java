package com.example.httpreading.repository;

import com.example.httpreading.domain.document.ChunkDoc;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 书籍章节片段 Repository
 * 注意：KNN 向量检索在 ChunkService 中通过 ElasticsearchOperations 原生查询实现
 */
@Repository
public interface ChunkRepository extends ElasticsearchRepository<ChunkDoc, String> {

    /** 根据 bookId 删除所有 chunks */
    void deleteByBookId(Long bookId);

    /** 查询某本书的所有 chunks */
    List<ChunkDoc> findByBookId(Long bookId);

    /** 查询某本书某个章节的所有 chunks */
    List<ChunkDoc> findByBookIdAndChapterIndex(Long bookId, Integer chapterIndex);
}
