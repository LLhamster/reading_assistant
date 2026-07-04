package com.example.httpreading.controller;

import java.util.List;

import com.example.httpreading.api.CommonResponse;
import com.example.httpreading.dto.ReadingAnnotationDtos.CreateRequest;
import com.example.httpreading.dto.ReadingAnnotationDtos.Response;
import com.example.httpreading.dto.ReadingAnnotationDtos.UpdateRequest;
import com.example.httpreading.security.JwtAuthFilter;
import com.example.httpreading.service.ReadingAnnotationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
@Validated
public class ReadingAnnotationController {
    private final ReadingAnnotationService annotationService;

    public ReadingAnnotationController(ReadingAnnotationService annotationService) {
        this.annotationService = annotationService;
    }

    @GetMapping("/books/{bookId}/annotations")
    public CommonResponse<List<Response>> listBook(@PathVariable @Positive Long bookId,
                                                   HttpServletRequest request) {
        return CommonResponse.success(annotationService.listBook(userId(request), bookId));
    }

    @GetMapping("/books/{bookId}/chapters/{chapterIndex}/annotations")
    public CommonResponse<List<Response>> listChapter(@PathVariable @Positive Long bookId,
                                                      @PathVariable @Positive Integer chapterIndex,
                                                      HttpServletRequest request) {
        return CommonResponse.success(annotationService.listChapter(userId(request), bookId, chapterIndex));
    }

    @PostMapping("/books/{bookId}/chapters/{chapterIndex}/annotations")
    public CommonResponse<Response> create(@PathVariable @Positive Long bookId,
                                           @PathVariable @Positive Integer chapterIndex,
                                           @Valid @RequestBody CreateRequest body,
                                           HttpServletRequest request) {
        return CommonResponse.success(annotationService.create(userId(request), bookId, chapterIndex, body));
    }

    @PutMapping("/annotations/{annotationId}")
    public CommonResponse<Response> update(@PathVariable @Positive Long annotationId,
                                           @Valid @RequestBody UpdateRequest body,
                                           HttpServletRequest request) {
        return CommonResponse.success(annotationService.update(userId(request), annotationId, body));
    }

    @DeleteMapping("/annotations/{annotationId}")
    public CommonResponse<Void> delete(@PathVariable @Positive Long annotationId,
                                       HttpServletRequest request) {
        annotationService.delete(userId(request), annotationId);
        return CommonResponse.success(null);
    }

    private Long userId(HttpServletRequest request) {
        Object value = request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        if (!(value instanceof Long id)) {
            throw new IllegalArgumentException("未登录");
        }
        return id;
    }
}
