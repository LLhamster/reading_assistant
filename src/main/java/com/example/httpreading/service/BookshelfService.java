package com.example.httpreading.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.example.httpreading.repository.*;
import com.example.httpreading.domain.entity.*;
import com.example.httpreading.domain.user.Bookshelf;
import com.example.httpreading.dto.BookshelfItemResponse;
import com.example.httpreading.api.ErrorCode;

@Service
public class BookshelfService {
    private BookshelfRepository bookshelfRepository;
    private BooksRepository booksRepository;
    private BookshelfRagService bookshelfRagService;

    public BookshelfService(BookshelfRepository bookshelfRepository,
                            BooksRepository booksRepository,
                            BookshelfRagService bookshelfRagService){
        this.bookshelfRepository = bookshelfRepository;
        this.booksRepository = booksRepository;
        this.bookshelfRagService = bookshelfRagService;
    }

    @Transactional
    public List<BookshelfItemResponse> getBookshelf(Long userId){
        List<Bookshelf> bookshelf = bookshelfRepository.findByUserId(userId);
        List<Long> bookIds = bookshelf.stream().map(Bookshelf::getBookId).collect(Collectors.toList());
        if(bookIds == null || bookIds.isEmpty())return List.of();
        Map<Long, Bookshelf> shelfByBookId = bookshelf.stream()
            .collect(Collectors.toMap(Bookshelf::getBookId, item -> item, (left, right) -> left));
        return booksRepository.findAllById(bookIds).stream()
            .map(book -> {
                Bookshelf item = shelfByBookId.get(book.getId());
                return new BookshelfItemResponse(
                    book,
                    item == null ? "NONE" : item.getRagStatus(),
                    item == null ? null : item.getRagStartedAt(),
                    item == null ? null : item.getRagFinishedAt(),
                    item == null ? null : item.getRagError());
            })
            .collect(Collectors.toList());
    }

    @Transactional
    public void setBookshelf(Long userId, Long bookId){
        boolean exist = bookshelfRepository.findByUserIdAndBookId(userId, bookId).isPresent();
        if(exist)return;
        Bookshelf bookshelf = new Bookshelf();
        bookshelf.setBookId(bookId);
        bookshelf.setCreatedAt(LocalDateTime.now());
        bookshelf.setUserId(userId);
        bookshelf.setRagStatus("NONE");
        bookshelfRepository.save(bookshelf);    
    }

    @Transactional
    public BookshelfItemResponse startRagBuild(Long userId, Long bookId) {
        Bookshelf bookshelf = bookshelfRepository.findByUserIdAndBookId(userId, bookId)
            .orElseThrow(() -> ErrorCode.FORBIDDEN.toException("请先将书籍加入书架"));
        Books book = booksRepository.findById(bookId)
            .orElseThrow(() -> ErrorCode.BOOK_NOT_FOUND.toException("书籍不存在"));

        if ("INDEXING".equals(bookshelf.getRagStatus())) {
            return new BookshelfItemResponse(book, bookshelf.getRagStatus(),
                bookshelf.getRagStartedAt(), bookshelf.getRagFinishedAt(), bookshelf.getRagError());
        }

        bookshelf.setRagStatus("INDEXING");
        bookshelf.setRagStartedAt(LocalDateTime.now());
        bookshelf.setRagFinishedAt(null);
        bookshelf.setRagError(null);
        bookshelfRepository.save(bookshelf);

        startRagAfterCommit(bookshelf.getId(), bookId);
        return new BookshelfItemResponse(book, bookshelf.getRagStatus(),
            bookshelf.getRagStartedAt(), bookshelf.getRagFinishedAt(), bookshelf.getRagError());
    }

    private void startRagAfterCommit(Long bookshelfId, Long bookId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
            && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    bookshelfRagService.rebuildRagAsync(bookshelfId, bookId);
                }
            });
            return;
        }
        bookshelfRagService.rebuildRagAsync(bookshelfId, bookId);
    }

}
