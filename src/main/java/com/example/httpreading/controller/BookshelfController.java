package com.example.httpreading.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.httpreading.service.BookshelfService;
import com.example.httpreading.dto.BookshelfItemResponse;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;

import com.example.httpreading.domain.entity.*;
import com.example.httpreading.api.*;

@RestController
@RequestMapping("/api/user/bookshelf")
@Validated
public class BookshelfController {
    
    private static final Logger log = LoggerFactory.getLogger(BookshelfController.class);
    
    private BookshelfService bookshelfService;
    public BookshelfController(BookshelfService bookshelfService){
        this.bookshelfService = bookshelfService;
    }

    private Long getUserId(HttpServletRequest request){
        Object userId = request.getAttribute("userId");
        if (userId == null) {
            throw new IllegalArgumentException("未登录");
        }
        return (Long) userId;
    }

    @GetMapping
    public CommonResponse<List<BookshelfItemResponse>> getBookshelf(HttpServletRequest request){
        Long userId = getUserId(request);
        log.info("获取用户书架 - userId:{}", userId);
        List<BookshelfItemResponse> books = bookshelfService.getBookshelf(userId);
        log.info("获取用户书架完成 - userId:{}, 共 {} 本书", userId, books.size());
        return CommonResponse.success(books);
    }

    @PostMapping("/{bookId}")
    public void setBookshelf(HttpServletRequest request, @PathVariable @Positive Long bookId){
        Long userId = getUserId(request);
        log.info("添加书籍到书架 - userId:{}, bookId:{}", userId, bookId);
        bookshelfService.setBookshelf(userId, bookId);
        log.info("添加书籍到书架完成 - userId:{}, bookId:{}", userId, bookId);
    }    

    @PostMapping("/{bookId}/rag")
    public CommonResponse<BookshelfItemResponse> buildRag(HttpServletRequest request,
                                                          @PathVariable @Positive Long bookId){
        Long userId = getUserId(request);
        log.info("用户触发书架 RAG 构建 - userId:{}, bookId:{}", userId, bookId);
        return CommonResponse.success(bookshelfService.startRagBuild(userId, bookId));
    }

}
