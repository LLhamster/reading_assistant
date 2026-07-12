package com.example.httpreading.controller;

import java.util.List;

import com.example.httpreading.api.CommonResponse;
import com.example.httpreading.dto.mobile.MobileDtos.MobileBookSummary;
import com.example.httpreading.dto.mobile.MobileDtos.MobileChapterContent;
import com.example.httpreading.dto.mobile.MobileDtos.MobileChapterSummary;
import com.example.httpreading.dto.mobile.MobileDtos.MobilePageResponse;
import com.example.httpreading.service.BooksService;
import com.example.httpreading.service.ChaptersService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.Page;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mobile/books")
@Validated
public class MobileBooksController {
    private final BooksService booksService;
    private final ChaptersService chaptersService;

    public MobileBooksController(BooksService booksService, ChaptersService chaptersService) {
        this.booksService = booksService;
        this.chaptersService = chaptersService;
    }

    @GetMapping
    public CommonResponse<MobilePageResponse<MobileBookSummary>> list(
            @RequestParam(required = false) String keyString,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int pageSize,
            @RequestParam(defaultValue = "updatedAt") String sortBy,
            @RequestParam(defaultValue = "desc") String order) {
        Page<MobileBookSummary> books = booksService
            .requestBooks(keyString, category, page, pageSize, sortBy, order)
            .map(MobileBookSummary::from);
        return CommonResponse.success(MobilePageResponse.from(books));
    }

    @GetMapping("/{bookId}")
    public CommonResponse<MobileBookSummary> detail(@PathVariable @Positive Long bookId) {
        return CommonResponse.success(MobileBookSummary.from(booksService.getBooks(bookId)));
    }

    @GetMapping("/{bookId}/chapters")
    public CommonResponse<List<MobileChapterSummary>> chapters(@PathVariable @Positive Long bookId) {
        return CommonResponse.success(chaptersService.listChapters(bookId).stream()
            .map(MobileChapterSummary::from)
            .toList());
    }

    @GetMapping("/{bookId}/chapters/{chapterIndex}")
    public CommonResponse<MobileChapterContent> chapter(@PathVariable @Positive Long bookId,
                                                        @PathVariable @Positive int chapterIndex) {
        return CommonResponse.success(MobileChapterContent.from(chaptersService.getContent(bookId, chapterIndex)));
    }
}
