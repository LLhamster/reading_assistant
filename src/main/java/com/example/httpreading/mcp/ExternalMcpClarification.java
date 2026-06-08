package com.example.httpreading.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

public class ExternalMcpClarification {
    private final String text;
    private final Map<String, Object> value;

    public ExternalMcpClarification(String text, Map<String, Object> value) {
        this.text = text == null ? "" : text;
        this.value = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }

    public String getText() {
        return text;
    }

    public Map<String, Object> getValue() {
        return value;
    }
}
