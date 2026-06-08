package com.example.httpreading.service.ai;

import java.util.Map;

public record ToolStep(String toolName, Map<String, Object> arguments, String purpose) {
    public ToolStep {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        purpose = purpose == null ? "" : purpose;
    }
}
