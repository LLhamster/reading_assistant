package com.example.httpreading.dto.cognition;

public record ConceptResolutionRequest(
    String eventId,
    String userId,
    Long bookId,
    Integer chapterIndex,
    String sessionId,
    String question,
    String selectedText,
    String selectedContext,
    String chapterTitle,
    String chapterContent,
    String recentDialogue
) {
}
