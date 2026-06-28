package com.example.httpreading.evolution;

public record PromptOverride(String plannerPatch, String finalAnswerPatch) {
    public PromptOverride {
        plannerPatch = safe(plannerPatch);
        finalAnswerPatch = safe(finalAnswerPatch);
    }

    public static PromptOverride none() {
        return new PromptOverride("", "");
    }

    public static PromptOverride finalAnswerOnly(String finalAnswerPatch) {
        return new PromptOverride("", finalAnswerPatch);
    }

    public boolean isEmpty() {
        return plannerPatch.isBlank() && finalAnswerPatch.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
