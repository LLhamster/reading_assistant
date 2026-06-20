package com.example.httpreading.service.profile;

import java.util.Comparator;
import java.util.List;

import com.example.httpreading.domain.profile.UserKnowledgeState;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeStateInsightService {
    private final UserKnowledgeStateService knowledgeStateService;
    private final BookCategoryService bookCategoryService;

    public KnowledgeStateInsightService(UserKnowledgeStateService knowledgeStateService,
                                        BookCategoryService bookCategoryService) {
        this.knowledgeStateService = knowledgeStateService;
        this.bookCategoryService = bookCategoryService;
    }

    public List<NextReadingRecommendation> recommendNextReadings(String userId, String domain) {
        String normalizedDomain = bookCategoryService.normalize(domain);
        return knowledgeStateService.listByUserAndDomain(userId, normalizedDomain).stream()
            .filter(state -> List.of("learning", "unknown", "exposed").contains(state.getLevel()))
            .sorted(Comparator
                .comparingInt((UserKnowledgeState state) -> recommendationRank(state.getLevel()))
                .thenComparing(UserKnowledgeState::getTopic))
            .map(state -> new NextReadingRecommendation(
                state.getDomain(),
                state.getTopic(),
                state.getLevel(),
                recommendationReason(state)))
            .toList();
    }

    public List<KnowledgeRelation> relateToKnownKnowledge(String userId, String domain, String targetTopic) {
        String normalizedDomain = bookCategoryService.normalize(domain);
        return knowledgeStateService.listByUserAndDomain(userId, normalizedDomain).stream()
            .filter(state -> targetTopic == null || !targetTopic.equals(state.getTopic()))
            .filter(state -> List.of("basic_understood", "well_understood", "learning").contains(state.getLevel()))
            .sorted(Comparator
                .comparingInt((UserKnowledgeState state) -> relationRank(state.getLevel()))
                .thenComparing(UserKnowledgeState::getTopic))
            .map(state -> new KnowledgeRelation(
                state.getDomain(),
                state.getTopic(),
                state.getLevel(),
                relationReason(targetTopic, state)))
            .toList();
    }

    private int recommendationRank(String level) {
        if ("learning".equals(level)) {
            return 0;
        }
        if ("unknown".equals(level)) {
            return 1;
        }
        if ("exposed".equals(level)) {
            return 2;
        }
        return 3;
    }

    private int relationRank(String level) {
        if ("basic_understood".equals(level) || "well_understood".equals(level)) {
            return 0;
        }
        if ("learning".equals(level)) {
            return 1;
        }
        return 2;
    }

    private String recommendationReason(UserKnowledgeState state) {
        if ("learning".equals(state.getLevel())) {
            return state.getTopic() + "仍处于学习中，适合优先补强。"
                + extraReason(state.getWeaknessEvidence(), state.getSummary());
        }
        if ("unknown".equals(state.getLevel())) {
            return state.getTopic() + "尚未建立理解，可以作为下一步扩展。"
                + extraReason(state.getWeaknessEvidence(), state.getSummary());
        }
        return state.getTopic() + "已有接触但理解还浅，适合继续阅读。"
            + extraReason(state.getWeaknessEvidence(), state.getSummary());
    }

    private String relationReason(String targetTopic, UserKnowledgeState state) {
        String target = targetTopic == null || targetTopic.isBlank() ? "当前主题" : targetTopic;
        return target + "可以和" + state.getTopic() + "关联："
            + extraReason(state.getMasteredEvidence(), state.getSummary());
    }

    private String extraReason(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        return "";
    }

    public record NextReadingRecommendation(String domain, String topic, String level, String reason) {
    }

    public record KnowledgeRelation(String domain, String topic, String level, String reason) {
    }
}
