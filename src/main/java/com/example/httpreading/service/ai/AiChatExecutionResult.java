package com.example.httpreading.service.ai;

import com.example.httpreading.dto.AiChatResponse;

public record AiChatExecutionResult(AiChatResponse response, ChatPlan plan) {
}
