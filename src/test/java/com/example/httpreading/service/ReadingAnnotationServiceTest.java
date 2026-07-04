package com.example.httpreading.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.example.httpreading.domain.entity.Books;
import com.example.httpreading.domain.entity.Chapters;
import com.example.httpreading.domain.profile.ProfileGrowthEvidence;
import com.example.httpreading.domain.user.ReadingAnnotation;
import com.example.httpreading.dto.ReadingAnnotationDtos.CreateRequest;
import com.example.httpreading.dto.ReadingAnnotationDtos.UpdateRequest;
import com.example.httpreading.repository.BooksRepository;
import com.example.httpreading.repository.ChaptersRepository;
import com.example.httpreading.repository.ReadingAnnotationRepository;
import com.example.httpreading.service.profile.BookCategoryService;
import com.example.httpreading.service.profile.ProfileGrowthEvidenceService;
import com.example.httpreading.service.profile.ProfileVectorIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReadingAnnotationServiceTest {
    private ReadingAnnotationRepository annotationRepository;
    private ProfileGrowthEvidenceService evidenceService;
    private ProfileVectorIndexService vectorIndexService;
    private ReadingAnnotationService service;

    @BeforeEach
    void setUp() {
        annotationRepository = mock(ReadingAnnotationRepository.class);
        BooksRepository booksRepository = mock(BooksRepository.class);
        ChaptersRepository chaptersRepository = mock(ChaptersRepository.class);
        evidenceService = mock(ProfileGrowthEvidenceService.class);
        vectorIndexService = mock(ProfileVectorIndexService.class);
        BookCategoryService categoryService = mock(BookCategoryService.class);

        Books book = new Books();
        book.setId(46L);
        book.setTitle("乡土中国");
        Chapters chapter = new Chapters();
        chapter.setBookId(46L);
        chapter.setChapterIndex(3);
        chapter.setTitle("差序格局");
        when(booksRepository.findById(46L)).thenReturn(Optional.of(book));
        when(chaptersRepository.findByBookIdAndChapterIndex(46L, 3)).thenReturn(Optional.of(chapter));
        when(categoryService.resolve(46L, null)).thenReturn("社会学");
        when(annotationRepository.save(any())).thenAnswer(invocation -> {
            ReadingAnnotation item = invocation.getArgument(0);
            if (item.getId() == null) item.setId(12L);
            return item;
        });
        when(evidenceService.saveEvidence(any())).thenAnswer(invocation -> {
            ProfileGrowthEvidence evidence = invocation.getArgument(0);
            if (evidence.getId() == null) evidence.setId(20L);
            return evidence;
        });

        service = new ReadingAnnotationService(annotationRepository, booksRepository, chaptersRepository,
            evidenceService, vectorIndexService, categoryService);
    }

    @Test
    void pureHighlightDoesNotCreateProfileEvidence() {
        var response = service.create(7L, 46L, 3,
            new CreateRequest("差序格局", 10, 14, "所谓", "是一种", "yellow", "highlight", null));

        assertEquals(12L, response.id());
        assertEquals("yellow", response.color());
        assertEquals("highlight", response.markStyle());
        verify(evidenceService, never()).saveEvidence(any());
        verify(vectorIndexService, never()).upsertEvidenceVector(any());
    }

    @Test
    void noteCreatesLinkedProfileEvidence() {
        service.create(7L, 46L, 3,
            new CreateRequest("差序格局", 10, 14, "所谓", "是一种", "green", "wavy", "这像水波纹"));

        var captor = org.mockito.ArgumentCaptor.forClass(ProfileGrowthEvidence.class);
        verify(evidenceService).saveEvidence(captor.capture());
        assertEquals("7", captor.getValue().getUserId());
        assertEquals("reading_note", captor.getValue().getEvidenceType());
        assertEquals(12L, captor.getValue().getRelatedAnnotationId());
        assertEquals(46L, captor.getValue().getRelatedBookId());
        verify(vectorIndexService).upsertEvidenceVector(any());
    }

    @Test
    void cannotUpdateAnotherUsersAnnotation() {
        when(annotationRepository.findByIdAndUserId(12L, 8L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
            () -> service.update(8L, 12L, new UpdateRequest("red", "underline", "越权修改")));
        verify(annotationRepository, never()).save(any());
    }

    @Test
    void invalidColorIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> service.create(7L, 46L, 3,
                new CreateRequest("原文", 0, 2, "", "", "purple", "highlight", null)));
    }
}
