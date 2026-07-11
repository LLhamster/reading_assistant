package com.example.httpreading.service.cognition;

import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class ConceptNormalizationService {
    private static final List<String> PREFIX_NOISE = List.of("这个", "所谓的", "所谓", "一种", "一个", "这里的");
    private static final List<String> SUFFIX_NOISE = List.of("这个概念", "这一概念", "概念", "行为", "现象", "是什么意思", "是什么", "什么意思", "吗");

    public String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim()
            .replaceAll("[\\s　]+", "")
            .replaceAll("[，。！？、；：,.!?;:\"'“”‘’（）()【】\\[\\]《》<>]", "")
            .toLowerCase();
        boolean changed;
        do {
            changed = false;
            for (String prefix : PREFIX_NOISE) {
                if (normalized.startsWith(prefix)) {
                    normalized = normalized.substring(prefix.length());
                    changed = true;
                }
            }
            for (String suffix : SUFFIX_NOISE) {
                if (normalized.endsWith(suffix) && normalized.length() > suffix.length()) {
                    normalized = normalized.substring(0, normalized.length() - suffix.length());
                    changed = true;
                }
            }
        } while (changed);
        return normalized;
    }

    public boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
