package com.example.httpreading.evaluation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

final class JsonlEvaluationLoader {
    private final ObjectMapper objectMapper;

    JsonlEvaluationLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    <T> List<T> load(String resourcePath, Class<T> type) throws IOException {
        InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath);
        if (stream == null) {
            throw new IllegalStateException("Evaluation resource not found: " + resourcePath);
        }
        List<T> cases = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) {
                    continue;
                }
                try {
                    cases.add(objectMapper.readValue(value, type));
                } catch (IOException exception) {
                    throw new IOException(resourcePath + ":" + lineNumber + " is not valid JSONL", exception);
                }
            }
        }
        return List.copyOf(cases);
    }
}
