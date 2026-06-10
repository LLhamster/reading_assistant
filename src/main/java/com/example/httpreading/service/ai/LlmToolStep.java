package com.example.httpreading.service.ai;

import java.util.Map;

public record LlmToolStep(String toolName,
                          Map<String, Object> arguments,
                          String reason) {
    public LlmToolStep {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        reason = reason == null ? "" : reason;
    }
}
