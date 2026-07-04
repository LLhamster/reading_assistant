package com.example.httpreading.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.httpreading.api.GlobalExceptionHandler;
import com.example.httpreading.domain.user.Reading;
import com.example.httpreading.service.ReadingService;

@ExtendWith(MockitoExtension.class)
class ReadingControllerTest {

    @Mock
    private ReadingService readingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(new ReadingController(readingService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void getsProgressWithJwtRequestUserAttribute() throws Exception {
        Reading progress = progress(8L, 46L, 5, 320);
        when(readingService.getProgress(46L, 8L)).thenReturn(progress);

        mockMvc.perform(get("/api/user/books/46/progress").requestAttr("userId", 8L))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.chapterIndex").value(5))
            .andExpect(jsonPath("$.data.offset").value(320));
    }

    @Test
    void updatesProgressWithJwtRequestUserAttribute() throws Exception {
        Reading progress = progress(8L, 46L, 6, 125);
        when(readingService.updateProgress(46L, 8L, 6, 125)).thenReturn(progress);

        mockMvc.perform(post("/api/user/books/46/progress")
                .requestAttr("userId", 8L)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"chapterIndex\":6,\"offset\":125}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.chapterIndex").value(6))
            .andExpect(jsonPath("$.data.offset").value(125));

        verify(readingService).updateProgress(46L, 8L, 6, 125);
    }

    @Test
    void rejectsProgressRequestWithoutAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/api/user/books/46/progress"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("未登录或token已过期"));
    }

    private Reading progress(Long userId, Long bookId, int chapterIndex, int offset) {
        Reading reading = new Reading();
        reading.setUserId(userId);
        reading.setBookId(bookId);
        reading.setChapterIndex(chapterIndex);
        reading.setOffset(offset);
        return reading;
    }
}
