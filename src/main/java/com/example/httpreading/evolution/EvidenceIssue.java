package com.example.httpreading.evolution;

import java.util.List;

public record EvidenceIssue(EvidenceIssueType type,
                            String summary,
                            int count,
                            List<String> examples,
                            String correction) {
    public EvidenceIssue {
        type = type == null ? EvidenceIssueType.UNSUPPORTED_FACTUAL_CLAIM : type;
        summary = safe(summary);
        count = Math.max(1, count);
        examples = examples == null
            ? List.of()
            : examples.stream().filter(value -> value != null && !value.isBlank())
                .limit(2).toList();
        correction = safe(correction);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
