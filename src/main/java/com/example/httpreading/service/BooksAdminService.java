package com.example.httpreading.service;

import com.example.httpreading.domain.entity.Books;
import com.example.httpreading.domain.entity.Chapters;
import com.example.httpreading.api.BusinessException;
import com.example.httpreading.api.ErrorCode;
import com.example.httpreading.dto.BookCreateRequest;
import com.example.httpreading.dto.BookImportIndexResponse;
import com.example.httpreading.dto.BookImportResponse;
import com.example.httpreading.service.DocumentParseService.ParsedBook;
import com.example.httpreading.service.DocumentParseService.ParsedChapter;
import com.example.httpreading.mq.BookIndexProducer;
import com.example.httpreading.repository.BooksRepository;
import com.example.httpreading.repository.ChaptersRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class BooksAdminService {

    private static final Logger log = LoggerFactory.getLogger(BooksAdminService.class);

    private final BooksRepository booksRepository;
    private final ChaptersRepository chaptersRepository;
    private final BookIndexProducer bookIndexProducer;
    private final DocumentStorageService documentStorageService;
    private final DocumentParseService documentParseService;
    private final BookDeletionService bookDeletionService;

    public BooksAdminService(BooksRepository booksRepository,
                             ChaptersRepository chaptersRepository,
                             BookIndexProducer bookIndexProducer,
                             DocumentStorageService documentStorageService,
                             DocumentParseService documentParseService,
                             BookDeletionService bookDeletionService) {
        this.booksRepository = booksRepository;
        this.chaptersRepository = chaptersRepository;
        this.bookIndexProducer = bookIndexProducer;
        this.documentStorageService = documentStorageService;
        this.documentParseService = documentParseService;
        this.bookDeletionService = bookDeletionService;
    }

    /** 新增书籍（写入 MySQL，然后发 MQ 消息异步同步 ES） */
    @Transactional
    public Books createBook(BookCreateRequest req) {
        Books book = new Books();
        book.setTitle(req.getTitle());
        book.setAuthor(req.getAuthor());
        book.setIntro(req.getIntro() != null ? req.getIntro() : "");
        book.setStatus(req.getStatus() != null ? req.getStatus() : "连载中");
        book.setCoverUrl(req.getCoverUrl() != null ? req.getCoverUrl() : "");
        book.setCreatedAt(LocalDateTime.now());
        book.setUpdatedAt(LocalDateTime.now());
        Books saved = booksRepository.save(book);

        // 发 MQ 消息，异步同步到 ES
        bookIndexProducer.sendIndexMessage(saved.getId());
        log.info("新增书籍已保存，MQ 索引消息已发送 - bookId:{}, title:{}", saved.getId(), saved.getTitle());

        return saved;
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public BookImportResponse importBookFile(BookCreateRequest req, MultipartFile file) {
        String sourceHash = documentStorageService.sha256(file);
        Optional<Books> duplicate = booksRepository.findBySourceHash(sourceHash);
        if (duplicate.isPresent() && !"FAILED".equals(duplicate.get().getParseStatus())) {
            Books existing = duplicate.get();
            log.info("跳过重复书籍文件 - bookId:{}, filename:{}, sourceHash:{}",
                existing.getId(), file.getOriginalFilename(), sourceHash);
            return BookImportResponse.duplicate(existing);
        }

        LocalDateTime now = LocalDateTime.now();
        Books book = duplicate.orElseGet(Books::new);
        book.setTitle(resolveTitle(req, file));
        book.setAuthor(req.getAuthor() != null && !req.getAuthor().isBlank() ? req.getAuthor() : "未知作者");
        book.setIntro(req.getIntro() != null ? req.getIntro() : "");
        book.setStatus(req.getStatus() != null ? req.getStatus() : "连载中");
        book.setCoverUrl(req.getCoverUrl() != null ? req.getCoverUrl() : "");
        book.setSourceHash(sourceHash);
        book.setSourceOriginalName(cleanOriginalFilename(file));
        book.setParseStatus("PROCESSING");
        book.setParseError(null);
        if (book.getCreatedAt() == null) {
            book.setCreatedAt(now);
        }
        book.setUpdatedAt(now);
        Books saved = booksRepository.save(book);

        try {
            String sourcePath = documentStorageService.saveSourceFile(saved.getId(), file);
            saved.setSourceFilePath(sourcePath);
            saved.setSourceFormat(documentStorageService.format(file.getOriginalFilename()).toLowerCase(Locale.ROOT));

            ParsedBook parsedBook = documentParseService.parse(
                documentStorageService.resolveRelativePath(sourcePath),
                req.getTitle(),
                req.getAuthor(),
                saved.getId());
            if (req.getTitle() == null || req.getTitle().isBlank()) {
                saved.setTitle(parsedBook.title());
            }
            if (req.getAuthor() == null || req.getAuthor().isBlank()) {
                saved.setAuthor(parsedBook.author());
            }

            documentStorageService.replaceBookAssets(saved.getId(), parsedBook.assets());
            List<Chapters> chaptersToSave = replaceChapters(saved.getId(), parsedBook.chapters(), now);

            saved.setParseStatus("SUCCESS");
            saved.setParseError(null);
            saved.setUpdatedAt(LocalDateTime.now());
            saved = booksRepository.save(saved);

            bookIndexProducer.sendIndexMessage(saved.getId());
            log.info("书籍文件导入完成 - bookId:{}, title:{}, chapters:{}",
                saved.getId(), saved.getTitle(), chaptersToSave.size());
            return BookImportResponse.imported(saved);
        } catch (BusinessException e) {
            saved.setParseStatus("FAILED");
            saved.setParseError(e.getMessage());
            saved.setUpdatedAt(LocalDateTime.now());
            booksRepository.save(saved);
            throw e;
        }
    }

    @Transactional
    public BookImportIndexResponse refreshImportIndex() {
        Map<String, Long> hashes = new LinkedHashMap<>();
        List<Long> missingSourceBookIds = new ArrayList<>();
        int indexedCount = 0;
        int duplicateContentCount = 0;

        for (Books book : booksRepository.findAll()) {
            if (book.getSourceHash() != null && !book.getSourceHash().isBlank()) {
                if (!"FAILED".equals(book.getParseStatus())) {
                    hashes.putIfAbsent(book.getSourceHash(), book.getId());
                }
                continue;
            }
            if (book.getSourceFilePath() == null || book.getSourceFilePath().isBlank()) {
                missingSourceBookIds.add(book.getId());
                continue;
            }
            try {
                String hash = documentStorageService.sha256(book.getSourceFilePath());
                Optional<Books> canonical = booksRepository.findBySourceHash(hash);
                if (canonical.isPresent() && !canonical.get().getId().equals(book.getId())) {
                    if (!"FAILED".equals(canonical.get().getParseStatus())) {
                        hashes.putIfAbsent(hash, canonical.get().getId());
                    }
                    duplicateContentCount++;
                    continue;
                }
                book.setSourceHash(hash);
                booksRepository.saveAndFlush(book);
                if (!"FAILED".equals(book.getParseStatus())) {
                    hashes.put(hash, book.getId());
                }
                indexedCount++;
            } catch (BusinessException e) {
                missingSourceBookIds.add(book.getId());
                log.warn("旧书哈希索引失败 - bookId:{}, path:{}, reason:{}",
                    book.getId(), book.getSourceFilePath(), e.getMessage());
            }
        }

        return new BookImportIndexResponse(
            Map.copyOf(hashes),
            indexedCount,
            missingSourceBookIds.size(),
            duplicateContentCount,
            List.copyOf(missingSourceBookIds));
    }

    @Transactional
    public Books replaceBookFile(Long bookId, BookCreateRequest req, MultipartFile file) {
        Books book = booksRepository.findById(bookId)
            .orElseThrow(() -> ErrorCode.BOOK_NOT_FOUND.toException("书籍不存在"));
        String sourceHash = documentStorageService.sha256(file);
        booksRepository.findBySourceHash(sourceHash)
            .filter(existing -> !existing.getId().equals(bookId))
            .ifPresent(existing -> {
                throw ErrorCode.BAD_REQUEST.toException(
                    "该文件已属于 bookId=" + existing.getId() + "，不能重复替换");
            });

        String title = hasText(req.getTitle()) ? req.getTitle().trim() : book.getTitle();
        String author = hasText(req.getAuthor()) ? req.getAuthor().trim() : book.getAuthor();
        String stagedPath = documentStorageService.stageReplacementSourceFile(bookId, file);
        try {
            ParsedBook parsedBook = documentParseService.parse(
                documentStorageService.resolveRelativePath(stagedPath),
                title,
                author,
                bookId);
            LocalDateTime now = LocalDateTime.now();
            documentStorageService.replaceBookAssets(bookId, parsedBook.assets());
            List<Chapters> chapters = replaceChapters(bookId, parsedBook.chapters(), now);
            String sourcePath = documentStorageService.commitReplacementSourceFile(
                bookId, stagedPath, file.getOriginalFilename());

            book.setTitle(title);
            book.setAuthor(author);
            if (hasText(req.getIntro())) {
                book.setIntro(req.getIntro());
            }
            if (hasText(req.getStatus())) {
                book.setStatus(req.getStatus());
            }
            if (hasText(req.getCoverUrl())) {
                book.setCoverUrl(req.getCoverUrl());
            }
            book.setSourceFilePath(sourcePath);
            book.setSourceFormat(documentStorageService.format(file.getOriginalFilename()).toLowerCase(Locale.ROOT));
            book.setSourceHash(sourceHash);
            book.setSourceOriginalName(cleanOriginalFilename(file));
            book.setParseStatus("SUCCESS");
            book.setParseError(null);
            book.setUpdatedAt(now);
            Books saved = booksRepository.save(book);
            bookIndexProducer.sendIndexMessage(saved.getId());
            log.info("书籍文件替换完成 - bookId:{}, title:{}, chapters:{}",
                saved.getId(), saved.getTitle(), chapters.size());
            return saved;
        } finally {
            try {
                documentStorageService.deleteStagedSourceFile(stagedPath);
            } catch (BusinessException cleanupError) {
                log.warn("替换书籍临时文件清理失败 - bookId:{}, path:{}, reason:{}",
                    bookId, stagedPath, cleanupError.getMessage());
            }
        }
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public Books reparseBook(Long bookId) {
        Books book = booksRepository.findById(bookId)
            .orElseThrow(() -> ErrorCode.BOOK_NOT_FOUND.toException("书籍不存在"));
        if (book.getSourceFilePath() == null || book.getSourceFilePath().isBlank()) {
            throw ErrorCode.BAD_REQUEST.toException("该书没有可重新解析的原始文件路径");
        }

        LocalDateTime now = LocalDateTime.now();
        book.setParseStatus("PROCESSING");
        book.setParseError(null);
        booksRepository.save(book);

        try {
            ParsedBook parsedBook = documentParseService.parse(
                documentStorageService.resolveRelativePath(book.getSourceFilePath()),
                book.getTitle(),
                book.getAuthor(),
                book.getId());
            documentStorageService.replaceBookAssets(book.getId(), parsedBook.assets());
            List<Chapters> chapters = replaceChapters(book.getId(), parsedBook.chapters(), now);
            book.setParseStatus("SUCCESS");
            book.setParseError(null);
            book.setUpdatedAt(LocalDateTime.now());
            Books saved = booksRepository.save(book);
            bookIndexProducer.sendIndexMessage(saved.getId());
            log.info("书籍重新解析完成 - bookId:{}, title:{}, chapters:{}",
                saved.getId(), saved.getTitle(), chapters.size());
            return saved;
        } catch (BusinessException e) {
            book.setParseStatus("FAILED");
            book.setParseError(e.getMessage());
            book.setUpdatedAt(LocalDateTime.now());
            booksRepository.save(book);
            throw e;
        }
    }

    /** 完整删除书籍及其数据库、缓存、索引、向量和文件关联数据。 */
    public void deleteBook(Long bookId) {
        bookDeletionService.deleteBook(bookId);
        log.info("书籍及关联数据删除完成 - bookId:{}", bookId);
    }

    private List<Chapters> replaceChapters(Long bookId, List<ParsedChapter> parsedChapters, LocalDateTime now) {
        chaptersRepository.deleteByBookId(bookId);
        List<Chapters> chaptersToSave = new ArrayList<>();
        int chapterIndex = 1;
        for (ParsedChapter parsedChapter : parsedChapters) {
            String contentPath = documentStorageService.writeChapterText(
                bookId,
                chapterIndex,
                parsedChapter.content());
            String renderContentPath = documentStorageService.writeChapterHtml(
                bookId,
                chapterIndex,
                parsedChapter.contentHtml());

            Chapters chapter = new Chapters();
            chapter.setBookId(bookId);
            chapter.setChapterIndex(chapterIndex);
            chapter.setTitle(parsedChapter.title());
            chapter.setVolumeIndex(parsedChapter.volumeIndex());
            chapter.setVolumeTitle(parsedChapter.volumeTitle());
            chapter.setHierarchyLevel(parsedChapter.hierarchyLevel());
            chapter.setContent(null);
            chapter.setContentFilePath(contentPath);
            chapter.setRenderContentFilePath(renderContentPath);
            chapter.setCreatedAt(now);
            chapter.setUpdatedAt(now);
            chaptersToSave.add(chapter);
            chapterIndex++;
        }
        return chaptersRepository.saveAll(chaptersToSave);
    }

    private String resolveTitle(BookCreateRequest req, MultipartFile file) {
        if (req.getTitle() != null && !req.getTitle().isBlank()) {
            return req.getTitle();
        }
        String filename = file == null ? "" : file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            return "未命名书籍";
        }
        String cleanName = java.nio.file.Path.of(filename).getFileName().toString();
        int dot = cleanName.lastIndexOf('.');
        return dot > 0 ? cleanName.substring(0, dot) : cleanName;
    }

    private String cleanOriginalFilename(MultipartFile file) {
        String filename = file == null ? null : file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            return "unknown";
        }
        return java.nio.file.Path.of(filename).getFileName().toString();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
