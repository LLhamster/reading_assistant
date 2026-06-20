package com.example.httpreading.dto.profile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class ProfileDtos {
    private ProfileDtos() {
    }

    public record StyleProfileDto(
        Long id,
        String userId,
        String explanationStyle,
        String preferredDepth,
        boolean prefersExamples,
        boolean prefersStorytelling,
        boolean prefersStepByStep,
        List<String> avoidance,
        String summary,
        double confidence,
        LocalDateTime updatedAt) {
    }

    public record ReadingUnderstandingProfileDto(
        Long id,
        String userId,
        String bookCategory,
        String understandingLevel,
        String learningStage,
        List<String> strengths,
        List<String> weaknesses,
        List<String> preferredExplanation,
        List<String> backgroundNeeds,
        List<String> typicalQuestions,
        String summary,
        double confidence,
        Long lastEvidenceId,
        int evidenceCount,
        LocalDateTime updatedAt) {
    }

    public record UserKnowledgeStateDto(
        Long id,
        String userId,
        String domain,
        String topic,
        String knowledgeType,
        String level,
        double confidence,
        String masteredEvidence,
        String weaknessEvidence,
        Long relatedBookId,
        String relatedBookTitle,
        Integer relatedChapterIndex,
        List<Long> sourceEvidenceIds,
        String summary,
        LocalDateTime updatedAt) {
    }

    public record ProfileOverviewResponse(
        String userId,
        StyleProfileDto styleProfile,
        List<ReadingUnderstandingProfileDto> readingUnderstandingProfiles,
        List<UserKnowledgeStateDto> knowledgeStates) {
    }

    public record ProfileUpdateRequest(
        String userId,
        String sessionId,
        Long bookId,
        Integer chapterIndex,
        String bookCategory,
        String question) {
    }

    public record ProfileUpdateResponse(
        String status,
        String userId,
        String summary,
        List<String> changes,
        List<String> warnings,
        int usedMemoryCount) {
    }

    public record StyleProfilePatch(
        String explanationStyle,
        String preferredDepth,
        Boolean prefersExamples,
        Boolean prefersStorytelling,
        Boolean prefersStepByStep,
        List<String> avoidance,
        String summary,
        Double confidenceDelta) {
    }

    public record ReadingProfilePatch(
        String bookCategory,
        String understandingLevel,
        String learningStage,
        List<String> strengths,
        List<String> weaknesses,
        List<String> preferredExplanation,
        List<String> backgroundNeeds,
        List<String> typicalQuestions,
        String summary,
        Double confidenceDelta) {
    }

    public record KnowledgeStatePatch(
        String domain,
        String topic,
        String knowledgeType,
        String level,
        Double confidenceDelta,
        String masteredEvidence,
        String weaknessEvidence,
        String summary,
        Long relatedBookId,
        String relatedBookTitle,
        Integer relatedChapterIndex) {
    }

    public record NewEvidencePatch(
        String evidenceDomain,
        String evidenceType,
        String bookCategory,
        String content,
        Double importance,
        Long relatedBookId,
        String relatedBookTitle,
        Integer relatedChapterIndex) {
    }

    public record ProfileUpdatePatch(
        StyleProfilePatch stylePatch,
        List<ReadingProfilePatch> readingPatches,
        List<KnowledgeStatePatch> knowledgePatches,
        List<NewEvidencePatch> newEvidence,
        String summary) {
    }

    public record ProfileVectorHit(
        String sourceType,
        Long sourceId,
        String categoryCode,
        String bookCategory,
        String evidenceType,
        double score,
        String summary,
        Map<String, Object> detail) {
    }

    public record ProfileSearchResult(
        boolean matched,
        List<ProfileVectorHit> items,
        String message) {
    }
}
