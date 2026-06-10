package com.example.httpreading.service.ai;

import java.util.Map;

public record AvailableTool(String name,
                            String description,
                            Map<String, String> parameters,
                            boolean readOperation,
                            boolean writeOperation,
                            boolean requiresConfirmation,
                            boolean enabled) {
    public AvailableTool {
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
