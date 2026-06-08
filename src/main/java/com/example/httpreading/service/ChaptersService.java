package com.example.httpreading.service;


import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.httpreading.api.ErrorCode;
import com.example.httpreading.domain.entity.Chapters;
import com.example.httpreading.repository.ChaptersRepository;


@Service
public class ChaptersService {
    private ChaptersRepository chaptersRepository;
    private final DocumentStorageService documentStorageService;

    public ChaptersService(ChaptersRepository chaptersRepository,
                           DocumentStorageService documentStorageService){
        this.chaptersRepository = chaptersRepository;
        this.documentStorageService = documentStorageService;
    }

    public List<Chapters> listChapters(Long bookid){
        return chaptersRepository.findByBookIdOrderByChapterIndexAsc(bookid);
    }

    @Transactional(readOnly = true)
    public Chapters getContent(Long bookId, int chapterId){
        Chapters chapter = chaptersRepository.findByBookIdAndChapterIndex(bookId, chapterId)
                .orElseThrow(() -> ErrorCode.CHAPTER_NOT_FOUND
                .toException("未找到 bookId=" + bookId + ", chapterIndex=" + chapterId));
        String path = chapter.getContentFilePath();
        if(path != null && !path.isBlank()){
            chapter.setContent(documentStorageService.readText(path));
        }
        return chapter;
    }

    public String resolveContent(Chapters chapter) {
        if (chapter == null) {
            return "";
        }
        String path = chapter.getContentFilePath();
        if (path != null && !path.isBlank()) {
            return documentStorageService.readText(path);
        }
        return chapter.getContent() == null ? "" : chapter.getContent();
    }
}
