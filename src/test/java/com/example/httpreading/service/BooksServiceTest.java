package com.example.httpreading.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.httpreading.domain.entity.Books;
import com.example.httpreading.repository.BooksRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

@ExtendWith(MockitoExtension.class)
class BooksServiceTest {

    @Mock
    private BooksRepository booksRepository;
    
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    
    @Mock
    private ValueOperations<String, Object> valueOperations;
    
    @Mock
    private SearchService searchService;

    @Mock
    private ChaptersService chaptersService;

    private ObjectMapper objectMapper;

    private BooksService booksService;

    @BeforeEach // 每个测试方法执行前都会先执行这个初始化方法
    void setUp() { // 初始化测试对象
        objectMapper = new ObjectMapper(); // 创建 JSON 序列化和反序列化工具
        booksService = new BooksService(booksRepository, redisTemplate, objectMapper, searchService, chaptersService); // 创建被测试的 BooksService 对象
        ReflectionTestUtils.setField(booksService, "cacheEnabled", false); // 通过反射关闭缓存逻辑，避免测试依赖 Redis
    }

    @Test
    @DisplayName("getBooks - 禁用缓存时直接查数据库")
    void getBooks_withoutCache_returnsFromDb() {
        // given
        Books book = new Books();
        book.setId(1L);
        book.setTitle("测试书籍");
        book.setAuthor("测试作者");
        when(booksRepository.findById(1L)).thenReturn(Optional.of(book));

        // when
        Books result = booksService.getBooks(1L);

        // then
        assertNotNull(result);
        assertEquals("测试书籍", result.getTitle());
        assertEquals("测试作者", result.getAuthor());
        verify(booksRepository, times(1)).findById(1L);
    }

    @Test
    @DisplayName("getBooks - 数据库无记录返回null")
    void getBooks_notFound_returnsNull() {
        // given
        when(booksRepository.findById(999L)).thenReturn(Optional.empty());

        // when
        Books result = booksService.getBooks(999L);

        // then
        assertNull(result);
    }

    @Test
    @DisplayName("requestBooks - 分页查询")
    void requestBooks_withPagination_returnsPage() {
        // given
        Books book1 = new Books();
        book1.setId(1L);
        book1.setTitle("书籍1");
        Books book2 = new Books();
        book2.setId(2L);
        book2.setTitle("书籍2");
        Page<Books> page = new PageImpl<>(List.of(book1, book2));
        when(booksRepository.findAll(any(Pageable.class))).thenReturn(page);

        // when
        Page<Books> result = booksService.requestBooks(null, null, 0, 10, "updatedAt", "desc");

        // then
        assertNotNull(result);
        assertEquals(2, result.getTotalElements());
    }

    @Test
    @DisplayName("requestBooks - 按标题关键字搜索")
    void requestBooks_searchByKeyword_returnsFilteredResults() {
        // given
        Books book = new Books();
        book.setId(1L);
        book.setTitle("Java入门");
        Page<Books> page = new PageImpl<>(List.of(book));
        when(booksRepository.findByTitleContainingIgnoreCaseOrAuthorContainingIgnoreCase(
                eq("Java"), eq("Java"), any(Pageable.class)))
                .thenReturn(page);

        // when
        Page<Books> result = booksService.requestBooks("Java", null, 0, 10, "updatedAt", "desc");

        // then
        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals("Java入门", result.getContent().get(0).getTitle());
    }

    @Test
    @DisplayName("requestBooks - 按分类筛选")
    void requestBooks_filterByCategory_returnsFilteredResults() {
        // given
        Books book = new Books();
        book.setId(1L);
        book.setStatus("连载中");
        Page<Books> page = new PageImpl<>(List.of(book));
        when(booksRepository.findByStatus(eq("连载中"), any(Pageable.class))).thenReturn(page);

        // when
        Page<Books> result = booksService.requestBooks(null, "连载中", 0, 10, "updatedAt", "desc");

        // then
        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
    }

    @Test
    @DisplayName("requestBooks - 非法排序字段时兜底为updatedAt")
    void requestBooks_invalidSortField_defaultsToUpdatedAt() {
        // given
        Page<Books> page = new PageImpl<>(List.of());
        when(booksRepository.findAll(any(Pageable.class))).thenReturn(page);

        // when
        Page<Books> result = booksService.requestBooks(null, null, 0, 10, "invalidField", "desc");

        // then
        assertNotNull(result);
        verify(booksRepository).findAll(any(Pageable.class));
    }
}
