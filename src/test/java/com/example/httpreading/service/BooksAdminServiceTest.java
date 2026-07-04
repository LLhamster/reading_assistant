package com.example.httpreading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import com.example.httpreading.domain.entity.Books;
import com.example.httpreading.dto.BookCreateRequest;
import com.example.httpreading.dto.BookImportIndexResponse;
import com.example.httpreading.dto.BookImportResponse;
import com.example.httpreading.mq.BookIndexProducer;
import com.example.httpreading.repository.BooksRepository;
import com.example.httpreading.repository.ChaptersRepository;

@ExtendWith(MockitoExtension.class)
class BooksAdminServiceTest {

    @Mock
    private BooksRepository booksRepository;
    @Mock
    private ChaptersRepository chaptersRepository;
    @Mock
    private BookIndexProducer bookIndexProducer;
    @Mock
    private DocumentStorageService documentStorageService;
    @Mock
    private DocumentParseService documentParseService;
    @Mock
    private BookDeletionService bookDeletionService;

    @Test
    void duplicateSuccessfulImportReturnsExistingBookWithoutParsing() {
        Books existing = book(46L, "乡土中国", "SUCCESS");
        MockMultipartFile file = new MockMultipartFile(
            "file", "renamed.epub", "application/epub+zip", "same-content".getBytes());
        when(documentStorageService.sha256(file)).thenReturn("abc123");
        when(booksRepository.findBySourceHash("abc123")).thenReturn(Optional.of(existing));

        BookImportResponse response = service().importBookFile(new BookCreateRequest(), file);

        assertThat(response.id()).isEqualTo(46L);
        assertThat(response.importDisposition()).isEqualTo("DUPLICATE");
        verify(booksRepository, never()).save(any());
        verify(documentParseService, never()).parse(any(), any(), any(), any());
    }

    @Test
    void refreshImportIndexHashesLegacySourcesAndReportsMissingOnes() {
        Books legacy = book(11L, "旧书", "SUCCESS");
        legacy.setSourceFilePath("books/11/source/original.epub");
        Books missing = book(12L, "缺失源文件", "SUCCESS");
        missing.setSourceFilePath("books/12/source/original.epub");
        when(booksRepository.findAll()).thenReturn(List.of(legacy, missing));
        when(documentStorageService.sha256("books/11/source/original.epub")).thenReturn("hash11");
        when(documentStorageService.sha256("books/12/source/original.epub"))
            .thenThrow(com.example.httpreading.api.ErrorCode.RESOURCE_NOT_FOUND.toException("不存在"));
        when(booksRepository.findBySourceHash("hash11")).thenReturn(Optional.empty());

        BookImportIndexResponse response = service().refreshImportIndex();

        assertThat(response.hashes()).containsEntry("hash11", 11L);
        assertThat(response.indexedCount()).isEqualTo(1);
        assertThat(response.missingSourceBookIds()).containsExactly(12L);
        assertThat(legacy.getSourceHash()).isEqualTo("hash11");
        verify(booksRepository).saveAndFlush(legacy);
    }

    @Test
    void refreshImportIndexUsesCanonicalBookForLegacyDuplicateContent() {
        Books canonical = book(21L, "原书", "SUCCESS");
        canonical.setSourceHash("same-hash");
        Books duplicate = book(22L, "旧重复书", "SUCCESS");
        duplicate.setSourceFilePath("books/22/source/original.epub");
        when(booksRepository.findAll()).thenReturn(List.of(canonical, duplicate));
        when(documentStorageService.sha256("books/22/source/original.epub")).thenReturn("same-hash");
        when(booksRepository.findBySourceHash("same-hash")).thenReturn(Optional.of(canonical));

        BookImportIndexResponse response = service().refreshImportIndex();

        assertThat(response.hashes()).containsEntry("same-hash", 21L);
        assertThat(response.duplicateContentCount()).isEqualTo(1);
        assertThat(duplicate.getSourceHash()).isNull();
        verify(booksRepository, never()).saveAndFlush(duplicate);
    }

    @Test
    void refreshImportIndexDoesNotCauseFailedImportToBeSkipped() {
        Books failed = book(31L, "待重试", "FAILED");
        failed.setSourceHash("failed-hash");
        when(booksRepository.findAll()).thenReturn(List.of(failed));

        BookImportIndexResponse response = service().refreshImportIndex();

        assertThat(response.hashes()).doesNotContainKey("failed-hash");
    }

    @Test
    void replacesFileWhileKeepingBookIdAndMetadataByDefault() {
        Books existing = book(51L, "学会提问", "SUCCESS");
        existing.setStatus("完结");
        MockMultipartFile file = new MockMultipartFile(
            "file", "better.epub", "application/epub+zip", "better-content".getBytes());
        when(booksRepository.findById(51L)).thenReturn(Optional.of(existing));
        when(documentStorageService.sha256(file)).thenReturn("better-hash");
        when(booksRepository.findBySourceHash("better-hash")).thenReturn(Optional.empty());
        when(documentStorageService.stageReplacementSourceFile(51L, file))
            .thenReturn("books/51/source/replacement-test.epub");
        when(documentStorageService.resolveRelativePath("books/51/source/replacement-test.epub"))
            .thenReturn(Path.of("/tmp/replacement-test.epub"));
        when(documentParseService.parse(any(Path.class), anyString(), anyString(), anyLong()))
            .thenReturn(new DocumentParseService.ParsedBook(
                "学会提问",
                "作者",
                List.of(new DocumentParseService.ParsedChapter("新章节", "正文")),
                Map.of()));
        when(documentStorageService.writeChapterText(51L, 1, "正文"))
            .thenReturn("books/51/chapters/1.txt");
        when(chaptersRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(documentStorageService.commitReplacementSourceFile(
            51L, "books/51/source/replacement-test.epub", "better.epub"))
            .thenReturn("books/51/source/original.epub");
        when(documentStorageService.format("better.epub")).thenReturn("epub");
        when(booksRepository.save(existing)).thenReturn(existing);

        Books replaced = service().replaceBookFile(51L, new BookCreateRequest(), file);

        assertThat(replaced.getId()).isEqualTo(51L);
        assertThat(replaced.getTitle()).isEqualTo("学会提问");
        assertThat(replaced.getStatus()).isEqualTo("完结");
        assertThat(replaced.getSourceHash()).isEqualTo("better-hash");
        assertThat(replaced.getSourceFilePath()).isEqualTo("books/51/source/original.epub");
        assertThat(replaced.getParseStatus()).isEqualTo("SUCCESS");
        verify(documentStorageService).deleteStagedSourceFile("books/51/source/replacement-test.epub");
        verify(bookIndexProducer).sendIndexMessage(51L);
    }

    @Test
    void rejectsReplacementWhenFileBelongsToAnotherBook() {
        Books current = book(51L, "学会提问", "SUCCESS");
        Books other = book(52L, "其他书", "SUCCESS");
        MockMultipartFile file = new MockMultipartFile(
            "file", "duplicate.epub", "application/epub+zip", "same".getBytes());
        when(booksRepository.findById(51L)).thenReturn(Optional.of(current));
        when(documentStorageService.sha256(file)).thenReturn("same-hash");
        when(booksRepository.findBySourceHash("same-hash")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service().replaceBookFile(51L, new BookCreateRequest(), file))
            .isInstanceOf(com.example.httpreading.api.BusinessException.class)
            .hasMessageContaining("bookId=52");

        verify(documentStorageService, never()).stageReplacementSourceFile(anyLong(), any());
    }

    @Test
    void delegatesCompleteBookDeletion() {
        service().deleteBook(46L);

        verify(bookDeletionService).deleteBook(46L);
        verify(booksRepository, never()).deleteById(any());
    }

    private BooksAdminService service() {
        return new BooksAdminService(
            booksRepository,
            chaptersRepository,
            bookIndexProducer,
            documentStorageService,
            documentParseService,
            bookDeletionService);
    }

    private Books book(Long id, String title, String parseStatus) {
        Books book = new Books();
        book.setId(id);
        book.setTitle(title);
        book.setAuthor("作者");
        book.setParseStatus(parseStatus);
        return book;
    }
}
