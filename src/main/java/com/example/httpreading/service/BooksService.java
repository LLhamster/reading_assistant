package com.example.httpreading.service;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;
import com.example.httpreading.domain.entity.Chapters;

import com.example.httpreading.domain.entity.Books;
import com.example.httpreading.repository.BooksRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class BooksService{
    private BooksRepository booksRepository;
    private RedisTemplate<String, Object> redisTemplate;
    private ObjectMapper objectMapper;
    private SearchService searchService;
    private ChaptersService chaptersService;
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
        "updatedAt", "createdAt", "title", "author", "status"
    );
    @Value("${cache.enabled:true}")
    private boolean cacheEnabled;
    
    public BooksService(BooksRepository booksRepository,
                        RedisTemplate<String, Object> redisTemplate,
                        ObjectMapper objectMapper,
                        SearchService searchService,
                        ChaptersService chaptersService){
        this.booksRepository = booksRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.searchService = searchService;
        this.chaptersService = chaptersService;
    }

    public Page<Books> requestBooks(String keyString, String category, int page, int pageSize, String sortBy, String order){
        if (!ALLOWED_SORT_FIELDS.contains(sortBy)) {
            sortBy = "updatedAt"; // 非法值时兜底
        }        
        Sort.Direction direction = "asc".equalsIgnoreCase(order)?Sort.Direction.ASC : Sort.Direction.DESC;
        Sort sort = Sort.by(direction, sortBy);
        Pageable pageable = PageRequest.of(page, pageSize, sort);
        if (keyString != null && !keyString.isEmpty()) {
            return booksRepository.findByTitleContainingIgnoreCaseOrAuthorContainingIgnoreCase(keyString, keyString, pageable);
        } else if (category != null && !category.isEmpty()) {
            return booksRepository.findByStatus(category, pageable);  // 🆕
        } else {
            return booksRepository.findAll(pageable);
        }
    }

    public Books getBooks(Long bookid){
        // 禁用缓存时直接查数据库
        if (!cacheEnabled) {
            return booksRepository.findById(bookid).orElse(null);
        }
        
        // 使用 ObjectMapper 安全反序列化，避免 LinkedHashMap 问题
        Object cache = redisTemplate.opsForValue().get("bookid:" + bookid);
        if(cache != null){
            if(cache instanceof Books) return (Books)cache;
            // 如果是 LinkedHashMap，用 ObjectMapper 转换
            if(cache instanceof java.util.Map) {
                return objectMapper.convertValue(cache, Books.class);
            }
            return null;
        }
        else{
            Books books = booksRepository.findById(bookid).orElse(null);
            if(books != null){
                redisTemplate.opsForValue().set("bookid:" + bookid, books, 10, TimeUnit.MINUTES);
            }
            return books;
        }
    }

    /**
     * 保存或更新书籍（同时同步到 ES 索引）
     */
    public Books saveBook(Books book) {
        // 1. 先存 MySQL
        Books saved = booksRepository.save(book);
        
        // 2. 查章节信息（用于 ES 索引）
        List<Chapters> chapters = chaptersService.listChapters(saved.getId());
        List<String> titles = chapters.stream().map(Chapters::getTitle).collect(Collectors.toList());
        
        // 3. 同步到 ES
        searchService.indexBook(saved, titles);
        
        return saved;
    }

    /**
     * 批量同步所有书籍到 ES
     */
    public void syncAllBooksToEs() {
        List<Books> allBooks = booksRepository.findAll();
        for (Books book : allBooks) {
            try {
                List<Chapters> chapters = chaptersService.listChapters(book.getId());
                List<String> titles = chapters.stream().map(Chapters::getTitle).collect(Collectors.toList());
                searchService.indexBook(book, titles);
            } catch (Exception e) {
                // 单本失败不影响其他
            }
        }
    }
}
