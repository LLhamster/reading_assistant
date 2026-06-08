package com.example.httpreading.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.httpreading.dto.BooksSearchResult;
import com.example.httpreading.service.SearchService;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);
    private SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public List<BooksSearchResult> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        log.info("搜索请求 - keyword:{}, category:{}, page:{}, pageSize:{}", 
                keyword, category, page, pageSize);
        List<BooksSearchResult> results = searchService.search(keyword, category, page, pageSize);
        log.info("搜索完成 - 返回 {} 条结果", results.size());
        return results;
    }
}