package com.example.httpreading.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.httpreading.api.CommonResponse;
import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.dto.AiChatResponse;
import com.example.httpreading.service.AiChatService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/ai")
public class AiChatController {

    private static final Logger log = LoggerFactory.getLogger(AiChatController.class);

    private final AiChatService aiChatService;

    public AiChatController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    @PostMapping({"", "/chat"})
    public CommonResponse<AiChatResponse> chat(@Valid @RequestBody AiChatRequest body) {
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
    public CommonResponse<AiChatResponse> getAnswer(@Valid @RequestBody AiChatRequest body) {
        return chat(body);
    }
}
