package com.example.httpreading.service;

import java.util.List;
import java.util.Set;

import com.example.httpreading.domain.entity.Books;
import com.example.httpreading.domain.entity.Chapters;
import com.example.httpreading.domain.profile.ProfileGrowthEvidence;
import com.example.httpreading.domain.user.ReadingAnnotation;
import com.example.httpreading.dto.ReadingAnnotationDtos.CreateRequest;
import com.example.httpreading.dto.ReadingAnnotationDtos.Response;
import com.example.httpreading.dto.ReadingAnnotationDtos.UpdateRequest;
import com.example.httpreading.repository.BooksRepository;
import com.example.httpreading.repository.ChaptersRepository;
import com.example.httpreading.repository.ReadingAnnotationRepository;
import com.example.httpreading.service.profile.BookCategoryService;
import com.example.httpreading.service.profile.ProfileGrowthEvidenceService;
import com.example.httpreading.service.profile.ProfileVectorIndexService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReadingAnnotationService {
    private static final Set<String> COLORS = Set.of("yellow", "green", "blue", "red");
    private static final Set<String> MARK_STYLES = Set.of("highlight", "underline", "wavy");

    private final ReadingAnnotationRepository annotationRepository;
    private final BooksRepository booksRepository;
    private final ChaptersRepository chaptersRepository;
    private final ProfileGrowthEvidenceService evidenceService;
    private final ProfileVectorIndexService vectorIndexService;
    private final BookCategoryService bookCategoryService;

    public ReadingAnnotationService(ReadingAnnotationRepository annotationRepository,
                                    BooksRepository booksRepository,
                                    ChaptersRepository chaptersRepository,
                                    ProfileGrowthEvidenceService evidenceService,
                                    ProfileVectorIndexService vectorIndexService,
                                    BookCategoryService bookCategoryService) {
        this.annotationRepository = annotationRepository;
        this.booksRepository = booksRepository;
        this.chaptersRepository = chaptersRepository;
        this.evidenceService = evidenceService;
        this.vectorIndexService = vectorIndexService;
        this.bookCategoryService = bookCategoryService;
    }

    public List<Response> listBook(Long userId, Long bookId) {
        requireBook(bookId);
        return annotationRepository.findByUserIdAndBookIdOrderByUpdatedAtDesc(userId, bookId)
            .stream().map(this::toResponse).toList();
    }

    public List<Response> listChapter(Long userId, Long bookId, Integer chapterIndex) {
        requireChapter(bookId, chapterIndex);
        return annotationRepository
            .findByUserIdAndBookIdAndChapterIndexOrderByStartOffsetAsc(userId, bookId, chapterIndex)
            .stream().map(this::toResponse).toList();
    }

    @Transactional
    public Response create(Long userId, Long bookId, Integer chapterIndex, CreateRequest request) {
        requireChapter(bookId, chapterIndex);
        validateRange(request.startOffset(), request.endOffset(), request.selectedText());
        ReadingAnnotation annotation = new ReadingAnnotation();
        annotation.setUserId(userId);
        annotation.setBookId(bookId);
        annotation.setChapterIndex(chapterIndex);
        annotation.setSelectedText(request.selectedText().trim());
        annotation.setStartOffset(request.startOffset());
        annotation.setEndOffset(request.endOffset());
        annotation.setPrefixText(trimToNull(request.prefixText()));
        annotation.setSuffixText(trimToNull(request.suffixText()));
        annotation.setColor(normalizeColor(request.color()));
        annotation.setMarkStyle(normalizeMarkStyle(request.markStyle()));
        annotation.setNoteContent(trimToNull(request.noteContent()));
        annotation = annotationRepository.save(annotation);
        syncEvidence(annotation);
        return toResponse(annotation);
    }

    @Transactional
    public Response update(Long userId, Long annotationId, UpdateRequest request) {
        ReadingAnnotation annotation = owned(annotationId, userId);
        if (request.color() != null) {
            annotation.setColor(normalizeColor(request.color()));
        }
        if (request.markStyle() != null) {
            annotation.setMarkStyle(normalizeMarkStyle(request.markStyle()));
        }
        if (request.noteContent() != null) {
            annotation.setNoteContent(trimToNull(request.noteContent()));
        }
        annotation = annotationRepository.save(annotation);
        syncEvidence(annotation);
        return toResponse(annotation);
    }

    @Transactional
    public void delete(Long userId, Long annotationId) {
        ReadingAnnotation annotation = owned(annotationId, userId);
        evidenceService.findByAnnotationId(annotationId).ifPresent(evidence -> {
            evidence.setStatus("inactive");
            evidenceService.saveEvidence(evidence);
            vectorIndexService.deleteEvidenceVector(evidence);
        });
        annotationRepository.delete(annotation);
    }

    private void syncEvidence(ReadingAnnotation annotation) {
        ProfileGrowthEvidence evidence = evidenceService.findByAnnotationId(annotation.getId()).orElse(null);
        if (annotation.getNoteContent() == null) {
            if (evidence != null && "active".equals(evidence.getStatus())) {
                evidence.setStatus("inactive");
                evidenceService.saveEvidence(evidence);
                vectorIndexService.deleteEvidenceVector(evidence);
            }
            return;
        }
        Books book = requireBook(annotation.getBookId());
        if (evidence == null) {
            evidence = new ProfileGrowthEvidence();
            evidence.setUserId(String.valueOf(annotation.getUserId()));
            evidence.setEvidenceDomain("reading_understanding");
            evidence.setEvidenceType("reading_note");
            evidence.setRelatedAnnotationId(annotation.getId());
            evidence.setImportance(0.75d);
        }
        evidence.setStatus("active");
        if (evidence.getBookCategory() == null || evidence.getBookCategory().isBlank()) {
            evidence.setBookCategory(bookCategoryService.resolve(book.getId(), null));
        }
        evidence.setRelatedBookId(book.getId());
        evidence.setRelatedBookTitle(book.getTitle());
        evidence.setRelatedChapterIndex(annotation.getChapterIndex());
        evidence.setContent("用户笔记：" + annotation.getNoteContent()
            + "\n引用原文：" + annotation.getSelectedText());
        evidence = evidenceService.saveEvidence(evidence);
        vectorIndexService.upsertEvidenceVector(evidence);
    }

    private Response toResponse(ReadingAnnotation annotation) {
        String chapterTitle = chaptersRepository
            .findByBookIdAndChapterIndex(annotation.getBookId(), annotation.getChapterIndex())
            .map(Chapters::getTitle).orElse("");
        return new Response(annotation.getId(), annotation.getBookId(), annotation.getChapterIndex(),
            chapterTitle, annotation.getSelectedText(), annotation.getStartOffset(), annotation.getEndOffset(),
            annotation.getPrefixText(), annotation.getSuffixText(), annotation.getColor(),
            annotation.getMarkStyle(), annotation.getNoteContent(),
            annotation.getCreatedAt(), annotation.getUpdatedAt());
    }

    private ReadingAnnotation owned(Long annotationId, Long userId) {
        return annotationRepository.findByIdAndUserId(annotationId, userId)
            .orElseThrow(() -> new IllegalArgumentException("批注不存在或无权访问"));
    }

    private Books requireBook(Long bookId) {
        return booksRepository.findById(bookId)
            .orElseThrow(() -> new IllegalArgumentException("书籍不存在"));
    }

    private Chapters requireChapter(Long bookId, Integer chapterIndex) {
        requireBook(bookId);
        return chaptersRepository.findByBookIdAndChapterIndex(bookId, chapterIndex)
            .orElseThrow(() -> new IllegalArgumentException("章节不存在"));
    }

    private String normalizeColor(String color) {
        String normalized = color == null ? "" : color.trim().toLowerCase();
        if (!COLORS.contains(normalized)) {
            throw new IllegalArgumentException("不支持的划线颜色");
        }
        return normalized;
    }

    private String normalizeMarkStyle(String style) {
        String normalized = style == null || style.isBlank() ? "highlight" : style.trim().toLowerCase();
        if (!MARK_STYLES.contains(normalized)) {
            throw new IllegalArgumentException("不支持的划词样式");
        }
        return normalized;
    }

    private void validateRange(Integer start, Integer end, String selectedText) {
        if (start == null || end == null || start < 0 || end <= start) {
            throw new IllegalArgumentException("划线位置无效");
        }
        if (selectedText == null || selectedText.trim().isEmpty()) {
            throw new IllegalArgumentException("划线原文不能为空");
        }
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
