package com.example.httpreading.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.httpreading.domain.entity.Books;
import com.example.httpreading.domain.entity.Chapters;
import com.example.httpreading.dto.BookCreateRequest;
import com.example.httpreading.repository.ChaptersRepository;
import com.example.httpreading.service.BooksAdminService;
import com.example.httpreading.service.BooksService;
import com.example.httpreading.service.RagService;
import com.example.httpreading.service.SearchService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private BooksService booksService;
    private SearchService searchService;
    private BooksAdminService booksAdminService;
    private ChaptersRepository chaptersRepository;
    private RagService ragService;

    public AdminController(BooksService booksService, SearchService searchService,
                          BooksAdminService booksAdminService,
                          ChaptersRepository chaptersRepository,
                          RagService ragService) {
        this.booksService = booksService;
        this.searchService = searchService;
        this.booksAdminService = booksAdminService;
        this.chaptersRepository = chaptersRepository;
        this.ragService = ragService;
    }

    /** 新增书籍 */
    @PostMapping("/books")
    public Books createBook(@Valid @RequestBody BookCreateRequest req) {
        log.info("管理员新增书籍 - title:{}", req.getTitle());
        return booksAdminService.createBook(req);
    }

    /** 上传并导入书籍文件 */
    @PostMapping(value = "/books/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Books importBook(@RequestParam("file") MultipartFile file,
                            @RequestParam(value = "title", required = false) String title,
                            @RequestParam(value = "author", required = false) String author,
                            @RequestParam(value = "intro", required = false) String intro,
                            @RequestParam(value = "status", required = false) String status,
                            @RequestParam(value = "coverUrl", required = false) String coverUrl) {
        BookCreateRequest req = new BookCreateRequest();
        req.setTitle(title);
        req.setAuthor(author);
        req.setIntro(intro);
        req.setStatus(status);
        req.setCoverUrl(coverUrl);
        log.info("管理员导入书籍文件 - filename:{}, title:{}", file.getOriginalFilename(), title);
        return booksAdminService.importBookFile(req, file);
    }

    /** 重新解析已保存的原始书籍文件，不自动构建 RAG */
    @PostMapping("/books/{bookId}/reparse")
    public Books reparseBook(@PathVariable Long bookId) {
        log.info("管理员重新解析书籍文件 - bookId:{}", bookId);
        return booksAdminService.reparseBook(bookId);
    }

    /** 删除书籍 */
    @DeleteMapping("/books/{bookId}")
    public String deleteBook(@PathVariable Long bookId) {
        log.info("管理员删除书籍 - bookId:{}", bookId);
        booksAdminService.deleteBook(bookId);
        return "删除成功";
    }

    /** 手动同步一本书到 ES */
    @PostMapping("/sync/{bookId}")
    public String syncBook(@PathVariable Long bookId) {
        log.info("手动同步书籍到ES - bookId:{}", bookId);
        Books book = booksService.getBooks(bookId);
        if (book == null) {
            return "书籍不存在";
        }
        booksService.saveBook(book);
        return "同步成功";
    }

    /** 批量同步所有书籍到 ES */
    @PostMapping("/sync/all")
    public String syncAllBooks() {
        log.info("批量同步所有书籍到ES");
        booksService.syncAllBooksToEs();
        return "批量同步完成";
    }
    /*
    执行步骤，通过bookId，chapters以及title，传入rebuildIndexForBook，在rebuildIndexForBook中，通过设定
    的大小一步一步切分章节的内容，然后将其转换为向量，并且将其打包成数据库里的内容保存下来。
    */

    /** 为某本书构建 RAG chunk 索引（重新切分+向量化） */
    @PostMapping("/chunks/rebuild/{bookId}")
    public String rebuildChunks(@PathVariable Long bookId) {
        log.info("重建 RAG chunk 索引 - bookId:{}", bookId);
        Books book = booksService.getBooks(bookId);
        if (book == null) {
            return "书籍不存在";
        }
        List<Chapters> chapters = chaptersRepository.findByBookIdOrderByChapterIndexAsc(bookId);
        if (chapters.isEmpty()) {
            return "该书没有章节";
        }
        int count = ragService.rebuildIndexForBook(bookId, chapters, book.getTitle());
        return "chunk 索引重建完成，共 " + count + " 个片段";
    }

    /** 
     * 重新索引所有书籍（MySQL → ES）
     * 
     * 解决以下问题：
     * 1. MySQL 有数据但 ES 为空（应用重启、ES 被清空）
     * 2. MySQL 和 ES 数据不一致
     * 3. 需要手动同步所有数据
     * 
     * 调用示例：
     * POST http://localhost:8080/api/admin/reindex/all
     * 
     * 响应示例：
     * {
     *   "success": true,
     *   "message": "重新索引完成",
     *   "reindexedCount": 20,
     *   "durationMs": 1234
     * }
     */
    @PostMapping("/reindex/all")
    public Map<String, Object> reindexAllBooks() {
        log.info("接收到重新索引所有书籍的请求");
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            long startTime = System.currentTimeMillis();
            int count = searchService.reindexAllBooks();
            long duration = System.currentTimeMillis() - startTime;
            
            response.put("success", true);
            response.put("message", "重新索引完成");
            response.put("reindexedCount", count);
            response.put("durationMs", duration);
            
            log.info("重新索引成功完成 - 书籍数: {}, 耗时: {}ms", count, duration);
        } catch (Exception e) {
            log.error("重新索引失败", e);
            response.put("success", false);
            response.put("message", "重新索引失败: " + e.getMessage());
            response.put("errorType", e.getClass().getSimpleName());
            response.put("stackTrace", getStackTrace(e));
        }
        
        return response;
    }

    /** 
     * 获取异常堆栈跟踪信息
     * 用于调试
     */
    private String getStackTrace(Exception e) {
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] stackTrace = e.getStackTrace();
        for (int i = 0; i < Math.min(stackTrace.length, 5); i++) {
            sb.append(stackTrace[i].toString()).append("\n");
        }
        return sb.toString();
    }
}
