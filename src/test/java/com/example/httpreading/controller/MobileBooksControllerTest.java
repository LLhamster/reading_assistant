package com.example.httpreading.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.example.httpreading.domain.entity.Books;
import com.example.httpreading.domain.entity.Chapters;
import com.example.httpreading.service.BooksService;
import com.example.httpreading.service.ChaptersService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MobileBooksControllerTest {
    @Mock
    private BooksService booksService;

    @Mock
    private ChaptersService chaptersService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(new MobileBooksController(booksService, chaptersService))
            .build();
    }

    @Test
    void wrapsBookListInCommonResponse() throws Exception {
        Books book = new Books();
        book.setId(1L);
        book.setTitle("测试书籍");
        book.setAuthor("作者");
        when(booksService.requestBooks(isNull(), isNull(), eq(0), eq(10), eq("updatedAt"), eq("desc")))
            .thenReturn(new PageImpl<>(List.of(book)));

        mockMvc.perform(get("/api/mobile/books"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.content[0].title").value("测试书籍"))
            .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void returnsLightweightChapterList() throws Exception {
        Chapters chapter = new Chapters();
        chapter.setId(3L);
        chapter.setBookId(1L);
        chapter.setChapterIndex(2);
        chapter.setTitle("第二章");
        chapter.setContent("不应出现在目录列表中");
        when(chaptersService.listChapters(1L)).thenReturn(List.of(chapter));

        mockMvc.perform(get("/api/mobile/books/1/chapters"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].chapterIndex").value(2))
            .andExpect(jsonPath("$.data[0].content").doesNotExist());
    }

    @Test
    void returnsChapterContentForReader() throws Exception {
        Chapters chapter = new Chapters();
        chapter.setId(3L);
        chapter.setBookId(1L);
        chapter.setChapterIndex(2);
        chapter.setTitle("第二章");
        chapter.setContent("正文");
        when(chaptersService.getContent(1L, 2)).thenReturn(chapter);

        mockMvc.perform(get("/api/mobile/books/1/chapters/2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").value("正文"));
    }
}
