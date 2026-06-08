package com.example.httpreading.service;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.httpreading.domain.entity.Books;
import com.example.httpreading.domain.entity.Chapters;
import com.example.httpreading.domain.user.Bookshelf;
import com.example.httpreading.repository.BooksRepository;
import com.example.httpreading.repository.BookshelfRepository;
import com.example.httpreading.repository.ChaptersRepository;

@Service
public class BookshelfRagService {
    private static final Logger log = LoggerFactory.getLogger(BookshelfRagService.class);

    private final BookshelfRepository bookshelfRepository;
    private final BooksRepository booksRepository;
    private final ChaptersRepository chaptersRepository;
    private final RagService ragService;
    private final DocumentStorageService documentStorageService;

    public BookshelfRagService(BookshelfRepository bookshelfRepository,
                               BooksRepository booksRepository,
                               ChaptersRepository chaptersRepository,
                               RagService ragService,
                               DocumentStorageService documentStorageService) {
        this.bookshelfRepository = bookshelfRepository;
        this.booksRepository = booksRepository;
        this.chaptersRepository = chaptersRepository;
        this.ragService = ragService;
        this.documentStorageService = documentStorageService;
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rebuildRagAsync(Long bookshelfId, Long bookId) {
        try {
            Books book = booksRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("书籍不存在: " + bookId));
            List<Chapters> chapters = chaptersRepository.findByBookIdOrderByChapterIndexAsc(bookId);
            for (Chapters chapter : chapters) {
                if ((chapter.getContent() == null || chapter.getContent().isBlank())
                    && chapter.getContentFilePath() != null
                    && !chapter.getContentFilePath().isBlank()) {
                    chapter.setContent(documentStorageService.readText(chapter.getContentFilePath()));
                }
            }
            int count = ragService.rebuildIndexForBook(bookId, chapters, book.getTitle());
            markReady(bookshelfId);
            log.info("书架 RAG 构建完成 - bookshelfId:{}, bookId:{}, chunks:{}", bookshelfId, bookId, count);
        } catch (Exception e) {
            markFailed(bookshelfId, e);
            log.error("书架 RAG 构建失败 - bookshelfId:{}, bookId:{}, message:{}",
                bookshelfId, bookId, e.getMessage(), e);
        }
    }

    private void markReady(Long bookshelfId) {
        Bookshelf bookshelf = bookshelfRepository.findById(bookshelfId).orElse(null);
        if (bookshelf == null) {
            return;
        }
        bookshelf.setRagStatus("READY");
        bookshelf.setRagFinishedAt(LocalDateTime.now());
        bookshelf.setRagError(null);
        bookshelfRepository.save(bookshelf);
    }

    private void markFailed(Long bookshelfId, Exception exception) {
        Bookshelf bookshelf = bookshelfRepository.findById(bookshelfId).orElse(null);
        if (bookshelf == null) {
            return;
        }
        bookshelf.setRagStatus("FAILED");
        bookshelf.setRagFinishedAt(LocalDateTime.now());
        bookshelf.setRagError(exception.getMessage());
        bookshelfRepository.save(bookshelf);
    }
}
