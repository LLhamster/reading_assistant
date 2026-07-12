package com.example.httpreading.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.httpreading.api.CommonResponse;
import com.example.httpreading.api.ErrorCode;
import com.example.httpreading.security.JwtAuthFilter;
import com.example.httpreading.api.BusinessException;
import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.dto.AiChatResponse;
import com.example.httpreading.service.AiChatRateLimiter;
import com.example.httpreading.service.AiChatService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/ai")
public class AiChatController {

    private static final Logger log = LoggerFactory.getLogger(AiChatController.class);

    private final AiChatService aiChatService;
    private final AiChatRateLimiter rateLimiter;

    public AiChatController(AiChatService aiChatService, AiChatRateLimiter rateLimiter) {
        this.aiChatService = aiChatService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping({"", "/chat"})
    public CommonResponse<AiChatResponse> chat(@Valid @RequestBody AiChatRequest body, HttpServletRequest request) {
        Long userId = currentUserId(request);
        body.setUserId(String.valueOf(userId));
        if (!rateLimiter.tryAcquire(String.valueOf(userId))) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        }
        log.info("AI问答请求 - bookId:{}, chapterIndex:{}, question:{}",
                body.getBookId(), body.getChapterIndex(), body.getQuestion());

        AiChatResponse response = aiChatService.chat(body);

        log.info("AI问答完成 - bookId:{}, contextId:{}, answer长度:{} 字, 来源数:{}",
                body.getBookId(),
                response.getContextId(),
                response.getAnswer() == null ? 0 : response.getAnswer().length(),
                response.getSources() == null ? 0 : response.getSources().size());
        return CommonResponse.success(response);
    }

    @GetMapping
    public CommonResponse<AiChatResponse> getAnswer(@Valid @RequestBody AiChatRequest body, HttpServletRequest request) {
        return chat(body, request);
    }

    private Long currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        if (value instanceof Long id) {
            return id;
        }
        throw new BusinessException(ErrorCode.UNAUTHORIZED);
    }
}
