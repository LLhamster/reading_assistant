package com.example.httpreading.service;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.HighlightQuery;
import org.springframework.data.elasticsearch.core.query.highlight.Highlight;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightField;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightParameters;
import org.springframework.stereotype.Service;

import com.example.httpreading.domain.document.BooksDoc;
import com.example.httpreading.domain.entity.Books;
import com.example.httpreading.dto.BooksSearchResult;
import com.example.httpreading.repository.BooksDocRepository;
import com.example.httpreading.repository.BooksRepository;
import com.example.httpreading.repository.ChaptersRepository;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.WildcardQuery;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private ElasticsearchOperations elasticsearchOperations;
    private BooksDocRepository booksDocRepository;
    private ChaptersRepository chaptersRepository;
    private BooksRepository booksRepository;

    public SearchService(ElasticsearchOperations elasticsearchOperations,
	                         BooksDocRepository booksDocRepository,
	                         ChaptersRepository chaptersRepository,
	                         BooksRepository booksRepository) {
        this.elasticsearchOperations = elasticsearchOperations;
        this.booksDocRepository = booksDocRepository;
        this.chaptersRepository = chaptersRepository;
        this.booksRepository = booksRepository;
    }

    // ====== 索引操作 ======

    /** 把一本书从 MySQL 同步到 ES */
    public void indexBook(Books book, List<String> chapterTitles) {
        BooksDoc doc = new BooksDoc();
        doc.setId(book.getId());
        doc.setTitle(book.getTitle());
        doc.setAuthor(book.getAuthor());
        doc.setIntro(book.getIntro());
        doc.setStatus(book.getStatus());
        doc.setChapterTitles(String.join(" | ", chapterTitles));
        booksDocRepository.save(doc);
    }

    /** 删除 ES 中一本书 */
    public void deleteBook(Long bookId) {
        booksDocRepository.deleteById(bookId);
    }

    /** 获取某本书所有章节标题 */
    public List<String> getChapterTitles(Long bookId) {
        return chaptersRepository.findByBookIdOrderByChapterIndexAsc(bookId)
            .stream().map(c -> c.getTitle()).collect(Collectors.toList());
    }

    // ====== 搜索操作 ======

    /**
     * 搜索书籍
     * @param keyword 搜索关键词
     * @param category 分类过滤（null 表示不过滤）
     * @param page 第几页（从 0 开始）
     * @param pageSize 每页大小
     * @return 搜索结果（带高亮）
     */
    public List<BooksSearchResult> search(String keyword, String category, int page, int pageSize) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }

        // 1. 构建多字段匹配查询
        MultiMatchQuery multiMatch = MultiMatchQuery.of(m -> m
            .query(keyword)
            .fields("title^3", "author^2", "intro", "chapterTitles^2")
        );

        // 2. 构建 bool 查询：标准分词命中，或短关键词前缀命中
        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        boolBuilder.should(Query.of(q -> q.multiMatch(multiMatch)));
        String wildcardKeyword = wildcardPrefix(keyword);
        if (wildcardKeyword != null) {
            boolBuilder.should(wildcardQuery("title", wildcardKeyword));
            boolBuilder.should(wildcardQuery("author", wildcardKeyword));
            boolBuilder.should(wildcardQuery("chapterTitles", wildcardKeyword));
        }
        boolBuilder.minimumShouldMatch("1");

        // 3. 分类过滤
        if (category != null && !category.isEmpty()) {
            TermQuery termQuery = TermQuery.of(t -> t
                .field("status")
                .value(category)
            );
            boolBuilder.filter(Query.of(q -> q.term(termQuery)));
        }

        // 4. 高亮配置
        List<HighlightField> highlightFields = List.of(
            new HighlightField("title"),
            new HighlightField("author"),
            new HighlightField("intro"),
            new HighlightField("chapterTitles")
        );

        HighlightParameters highlightParams = HighlightParameters.builder()
            .withPreTags("<em>")
            .withPostTags("</em>")
            .build();

        Highlight highlight = new Highlight(highlightParams, highlightFields);
        HighlightQuery highlightQuery = new HighlightQuery(highlight, BooksDoc.class);

        // 5. 组装 NativeQuery
        NativeQuery query = NativeQuery.builder()
            .withQuery(Query.of(q -> q.bool(boolBuilder.build())))
            .withHighlightQuery(highlightQuery)
            .withPageable(PageRequest.of(page, pageSize))
            .build();

        // 6. 执行搜索
        SearchHits<BooksDoc> hits = elasticsearchOperations.search(query, BooksDoc.class);
        return hits.getSearchHits().stream()
            .map(hit -> new BooksSearchResult(hit.getContent(), hit.getHighlightFields()))
            .collect(Collectors.toList());
    }

    private Query wildcardQuery(String field, String value) {
        WildcardQuery wildcardQuery = WildcardQuery.of(w -> w
            .field(field)
            .value(value)
        );
        return Query.of(q -> q.wildcard(wildcardQuery));
    }

    private String wildcardPrefix(String keyword) {
        if (keyword == null) {
            return null;
        }
        String normalized = keyword.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized
            .replace("\\", "\\\\")
            .replace("*", "\\*")
            .replace("?", "\\?")
            + "*";
    }

    // ====== 重新索引操作 ======

    /**
     * 重新索引所有书籍（用于 MySQL 和 ES 不同步的场景）
     * 场景：
     * 1. ES 被清空但 MySQL 还有数据
     * 2. 应用重启后需要重新同步
     * 3. 手动修复 ES 数据不一致
     * 
     * @return 重新索引的书籍总数
     */
    public int reindexAllBooks() {
        log.info("========== 开始重新索引所有书籍 ==========");
        
        try {
            // 1. 清空 ES 中的所有书籍索引
            log.info("正在清空 ES 中的旧索引...");
            booksDocRepository.deleteAll();
            log.info("已清空 ES 中的旧索引");
            
            // 2. 从 MySQL 查出所有书籍
            log.info("正在从 MySQL 查询所有书籍...");
            List<Books> allBooks = booksRepository.findAll();
            log.info("从 MySQL 查出 {} 本书籍", allBooks.size());
            
            if (allBooks.isEmpty()) {
                log.warn("数据库中没有书籍，无需重新索引");
                return 0;
            }
            
            // 3. 逐本书籍重新索引
            int successCount = 0;
            int failureCount = 0;
            
            for (Books book : allBooks) {
                try {
                    log.debug("正在处理书籍 - bookId:{}, title:{}", book.getId(), book.getTitle());
                    
                    List<String> chapterTitles = getChapterTitles(book.getId());
                    log.debug("书籍 {} 有 {} 个章节", book.getId(), chapterTitles.size());
                    
                    indexBook(book, chapterTitles);
                    successCount++;
                    
                    log.debug("已重新索引书籍 - bookId:{}, title:{}", book.getId(), book.getTitle());
                } catch (Exception e) {
                    failureCount++;
                    log.error("重新索引书籍失败 - bookId:{}, title:{}, 错误: {}", 
                        book.getId(), book.getTitle(), e.getMessage(), e);
                }
            }
            
            log.info("========== 重新索引完成 - 成功: {}/{}, 失败: {} ==========", 
                successCount, allBooks.size(), failureCount);
            return successCount;
            
        } catch (Exception e) {
            log.error("重新索引所有书籍时发生严重错误", e);
            throw new RuntimeException("重新索引失败: " + e.getMessage(), e);
        }
    }
}
