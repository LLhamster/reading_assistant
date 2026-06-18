package com.example.httpreading.service.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.example.httpreading.domain.entity.Books;
import com.example.httpreading.repository.BooksRepository;
import com.example.httpreading.service.ModelClient;
import org.junit.jupiter.api.Test;

class BookCategoryServiceTest {
    @Test
    void explicitCategoryMustBeFixedEnum() {
        BookCategoryService service = new BookCategoryService(mock(BooksRepository.class), mock(ModelClient.class));

        assertEquals("社会学", service.normalize("社会学"));
        assertEquals("其他", service.normalize("Java"));
    }

    @Test
    void metadataKeywordWinsBeforeModel() {
        BooksRepository booksRepository = mock(BooksRepository.class);
        ModelClient modelClient = mock(ModelClient.class);
        Books book = new Books();
        book.setId(1L);
        book.setTitle("Java核心技术卷I");
        book.setIntro("一本编程实践书");
        when(booksRepository.findById(1L)).thenReturn(Optional.of(book));

        BookCategoryService service = new BookCategoryService(booksRepository, modelClient);

        assertEquals("技术", service.resolve(1L, null));
    }

    @Test
    void invalidModelCategoryFallsBackToOther() {
        BooksRepository booksRepository = mock(BooksRepository.class);
        ModelClient modelClient = mock(ModelClient.class);
        Books book = new Books();
        book.setId(2L);
        book.setTitle("未知书籍");
        book.setIntro("没有明显分类线索");
        when(booksRepository.findById(2L)).thenReturn(Optional.of(book));
        when(modelClient.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("玄学");

        BookCategoryService service = new BookCategoryService(booksRepository, modelClient);

        assertEquals("其他", service.resolve(2L, null));
    }
}
