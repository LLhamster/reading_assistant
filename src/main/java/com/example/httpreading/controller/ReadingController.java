package com.example.httpreading.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import com.example.httpreading.api.CommonResponse;
import com.example.httpreading.domain.user.Reading;
import com.example.httpreading.service.ReadingService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;

@RestController
@RequestMapping("/api/user/books")
@Validated
public class ReadingController {
    
    private static final Logger log = LoggerFactory.getLogger(ReadingController.class);
    
    private ReadingService readingService;
    public ReadingController(ReadingService readingService){
        this.readingService = readingService;
    }

    private Long getUserId(HttpServletRequest request){
        Object userId = request.getAttribute("userId");
        if (userId == null) {
            throw new IllegalArgumentException("未登录");
        }
        return (Long) userId;
    }

    @Operation(summary = "获取书籍的进度", description = "")
    @GetMapping("/{bookId}/progress")
    public CommonResponse<Reading> getProgress(@PathVariable @Positive Long bookId, HttpServletRequest request){
        Long userId = getUserId(request);
        log.info("获取阅读进度 - userId:{}, bookId:{}", userId, bookId);
        Reading reading = readingService.getProgress(bookId, userId);
        log.debug("获取阅读进度完成 - userId:{}, bookId:{}", userId, bookId);
        return CommonResponse.success(reading);
    }

    @PostMapping("/{bookId}/progress")
    public CommonResponse<Reading> updateProgress(@PathVariable @Positive Long bookId, 
                                        HttpServletRequest request,
                                            @RequestBody Map<String, Integer>body){
        
        Integer index = body.get("chapterIndex");
        Integer offset = body.getOrDefault("offset", 0);
        Long userId = getUserId(request);
        if(index == null || offset ==null) throw new IllegalArgumentException("记录进度时没有章节号");
        log.info("更新阅读进度 - userId:{}, bookId:{}, chapterIndex:{}, offset:{}", userId, bookId, index, offset);
        Reading reading = readingService.updateProgress(bookId, userId, index, offset);
        log.info("更新阅读进度完成 - userId:{}, bookId:{}", userId, bookId);
        return CommonResponse.success(reading);
    }
}
