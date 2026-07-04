package com.example.httpreading.controller;


import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.httpreading.domain.entity.Books;
import com.example.httpreading.domain.entity.Chapters;
import com.example.httpreading.service.BooksService;
import com.example.httpreading.service.ChaptersService;
import com.example.httpreading.service.DocumentStorageService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/books")
@Validated
public class BooksController {
    
    private static final Logger log = LoggerFactory.getLogger(BooksController.class);
    
    private BooksService booksService;
    private ChaptersService chaptersService;
    private DocumentStorageService documentStorageService;
    public BooksController(BooksService booksService,
                           ChaptersService chaptersService,
                           DocumentStorageService documentStorageService){
        this.booksService = booksService;
        this.chaptersService = chaptersService;
        this.documentStorageService = documentStorageService;
    }
    

    @GetMapping
    public Page<Books> listsBooks(@RequestParam(required = false) String keyString, 
                                @RequestParam(required = false) String category,
                                @RequestParam(defaultValue = "0") @Min(0) int page, 
                                @RequestParam(defaultValue = "10") @Min(1) @Max(100) int pageSize,
                                @RequestParam(defaultValue = "updatedAt") String sortBy,
                                @RequestParam(defaultValue = "desc") String order){
        log.info("查询书籍列表 - keyString:{}, category:{}, page:{}, pageSize:{}, sortBy:{}, order:{}", 
                keyString, category, page, pageSize, sortBy, order);
        Page<Books> result = booksService.requestBooks(keyString, category, page, pageSize, sortBy, order);
        log.info("查询书籍列表完成 - 返回 {} 条", result.getTotalElements());
        return result;
    }

    @GetMapping("/{bookid}")
    public Books getBooks(@PathVariable @Positive Long bookid){
        log.info("查询书籍详情 - bookId:{}", bookid);
        Books book = booksService.getBooks(bookid);
        log.debug("查询书籍详情完成 - book:{}", book);
        return book;
    }

    @GetMapping("/{bookid}/chapters")
    public List<Chapters> listChapters(@PathVariable @Positive Long bookid){
        log.info("查询书籍章节列表 - bookId:{}", bookid);
        List<Chapters> chapters = chaptersService.listChapters(bookid);
        log.info("查询书籍章节列表完成 - bookId:{}, 共 {} 章", bookid, chapters.size());
        return chapters;
    }

    @GetMapping("/{bookid}/chapters/{chapterid}")
    public Chapters getChaptersContent(@PathVariable @Positive Long bookid,
                                        @PathVariable @Positive int chapterid){
        log.info("查询章节内容 - bookId:{}, chapterId:{}", bookid, chapterid);
        Chapters chapter = chaptersService.getContent(bookid, chapterid);
        log.debug("查询章节内容完成 - bookId:{}, chapterId:{}", bookid, chapterid);
        return chapter;
    }

    @GetMapping("/{bookid}/assets")
    public ResponseEntity<Resource> getBookAsset(@PathVariable @Positive Long bookid,
                                                  @RequestParam String path) {
        DocumentStorageService.StoredAsset asset = documentStorageService.readBookAsset(bookid, path);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(asset.mediaType()))
            .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "sandbox; default-src 'none'")
            .body(new FileSystemResource(asset.path()));
    }

}
