package com.example.httpreading.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.web.servlet.MockMvc;

import com.example.httpreading.domain.entity.Books;
import com.example.httpreading.domain.entity.Chapters;
import com.example.httpreading.service.BooksService;
import com.example.httpreading.service.ChaptersService;
import com.example.httpreading.service.DocumentStorageService;
import com.example.httpreading.security.JwtService;
import org.springframework.boot.test.mock.mockito.MockBean;

@WebMvcTest(BooksController.class)
@AutoConfigureMockMvc(addFilters = false) // 禁用安全过滤器，简化测试
class BooksControllerTest {

    @TempDir
    Path tempDir;

    @Autowired
    private MockMvc mockMvc;

        @MockBean
        private JwtService jwtService;

    @MockBean
    private BooksService booksService;

    @MockBean
    private ChaptersService chaptersService;

    @MockBean
    private DocumentStorageService documentStorageService;

    @Test
    @DisplayName("GET /api/books - 分页查询书籍列表")
    void listBooks_withPagination_returnsPage() throws Exception {
        // given
        Books book = new Books();
        book.setId(1L);
        book.setTitle("测试书籍");
        book.setAuthor("测试作者");
        book.setStatus("连载中");
        Page<Books> page = new PageImpl<>(List.of(book));
        when(booksService.requestBooks(isNull(), isNull(), eq(0), eq(10), eq("updatedAt"), eq("desc")))
                .thenReturn(page);

        // when & then
        mockMvc.perform(get("/api/books")
                        .param("page", "0")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("测试书籍"))
                .andExpect(jsonPath("$.content[0].author").value("测试作者"));
    }

    @Test
    @DisplayName("GET /api/books?keyString=Java - 关键字搜索")
    void listBooks_withKeyword_returnsFilteredResults() throws Exception {
        // given
        Books book = new Books();
        book.setId(1L);
        book.setTitle("Java入门");
        Page<Books> page = new PageImpl<>(List.of(book));
        when(booksService.requestBooks(eq("Java"), isNull(), eq(0), eq(10), eq("updatedAt"), eq("desc")))
                .thenReturn(page);

        // when & then
        mockMvc.perform(get("/api/books")
                        .param("keyString", "Java"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Java入门"));
    }

    @Test
    @DisplayName("GET /api/books?category=连载中 - 分类筛选")
    void listBooks_withCategory_returnsFilteredResults() throws Exception {
        // given
        Books book = new Books();
        book.setId(1L);
        book.setStatus("连载中");
        Page<Books> page = new PageImpl<>(List.of(book));
        when(booksService.requestBooks(isNull(), eq("连载中"), eq(0), eq(10), eq("updatedAt"), eq("desc")))
                .thenReturn(page);

        // when & then
        mockMvc.perform(get("/api/books")
                        .param("category", "连载中"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("连载中"));
    }

    @Test
    @DisplayName("GET /api/books/{id} - 查询书籍详情")
    void getBooks_exists_returnsBook() throws Exception {
        // given
        Books book = new Books();
        book.setId(1L);
        book.setTitle("测试书籍");
        book.setAuthor("测试作者");
        when(booksService.getBooks(1L)).thenReturn(book);

        // when & then
        mockMvc.perform(get("/api/books/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.title").value("测试书籍"));
    }

    @Test
    @DisplayName("GET /api/books/{id} - 书籍不存在返回null")
    void getBooks_notExists_returnsNull() throws Exception {
        // given
        when(booksService.getBooks(999L)).thenReturn(null);

        // when & then
        mockMvc.perform(get("/api/books/999"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/books/{id}/chapters - 查询章节列表")
    void listChapters_returnsChapterList() throws Exception {
        // given
        Chapters chapter1 = new Chapters();
        chapter1.setId(1L);
        chapter1.setChapterIndex(1);
        chapter1.setTitle("第一章");
        Chapters chapter2 = new Chapters();
        chapter2.setId(2L);
        chapter2.setChapterIndex(2);
        chapter2.setTitle("第二章");
        when(chaptersService.listChapters(1L)).thenReturn(List.of(chapter1, chapter2));

        // when & then
        mockMvc.perform(get("/api/books/1/chapters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].chapterIndex").value(1))
                .andExpect(jsonPath("$[0].title").value("第一章"))
                .andExpect(jsonPath("$[1].chapterIndex").value(2))
                .andExpect(jsonPath("$[1].title").value("第二章"));
    }

    @Test
    @DisplayName("GET /api/books/{bookid}/chapters/{chapterid} - 查询章节内容")
    void getChaptersContent_returnsChapter() throws Exception {
        // given
        Chapters chapter = new Chapters();
        chapter.setId(1L);
        chapter.setChapterIndex(1);
        chapter.setTitle("第一章");
        chapter.setContent("这是第一章的内容");
        when(chaptersService.getContent(1L, 1)).thenReturn(chapter);

        // when & then
        mockMvc.perform(get("/api/books/1/chapters/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapterIndex").value(1))
                .andExpect(jsonPath("$.title").value("第一章"))
                .andExpect(jsonPath("$.content").value("这是第一章的内容"));
    }

    @Test
    @DisplayName("GET /api/books/{id}/assets - 返回图片及安全缓存头")
    void getBookAsset_returnsImage() throws Exception {
        Path image = tempDir.resolve("test.png");
        byte[] bytes = new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        };
        Files.write(image, bytes);
        when(documentStorageService.readBookAsset(1L, "OPS/test.png"))
            .thenReturn(new DocumentStorageService.StoredAsset(image, "image/png"));

        mockMvc.perform(get("/api/books/1/assets").param("path", "OPS/test.png"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/png"))
            .andExpect(content().bytes(bytes))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Content-Security-Policy", "sandbox; default-src 'none'"))
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("immutable")));
    }

    @Test
    @DisplayName("GET /api/books - 升序排列")
    void listBooks_ascendingOrder_returnsSortedResults() throws Exception {
        // given
        Books book1 = new Books();
        book1.setId(1L);
        book1.setTitle("A书籍");
        Books book2 = new Books();
        book2.setId(2L);
        book2.setTitle("B书籍");
        Page<Books> page = new PageImpl<>(List.of(book1, book2));
        when(booksService.requestBooks(isNull(), isNull(), eq(0), eq(10), eq("title"), eq("asc")))
                .thenReturn(page);

        // when & then
        mockMvc.perform(get("/api/books")
                        .param("sortBy", "title")
                        .param("order", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("A书籍"));
    }
}
