package com.example.httpreading.service.profile;

import java.util.List;

import com.example.httpreading.domain.profile.UserKnowledgeState;
import com.example.httpreading.dto.profile.ProfileDtos.KnowledgeStatePatch;
import com.example.httpreading.repository.UserKnowledgeStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserKnowledgeStateService {
    private static final List<String> LEVELS = List.of("unknown", "exposed", "learning",
        "basic_understood", "well_understood");
    private static final List<String> KNOWLEDGE_TYPES = List.of("concept", "person", "event",
        "theory", "method", "case", "other");

    private final UserKnowledgeStateRepository repository;
    private final ProfileJson json;
    private final BookCategoryService bookCategoryService;

    public UserKnowledgeStateService(UserKnowledgeStateRepository repository,
                                     ProfileJson json,
                                     BookCategoryService bookCategoryService) {
        this.repository = repository;
        this.json = json;
        this.bookCategoryService = bookCategoryService;
    }

    public List<UserKnowledgeState> listByUser(String userId) {
        return repository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    public List<UserKnowledgeState> listByUserAndDomain(String userId, String domain) {
        return repository.findByUserIdAndDomainOrderByUpdatedAtDesc(userId, bookCategoryService.normalize(domain));
    }

    @Transactional
    public UserKnowledgeState updateKnowledgeState(String userId,
                                                   KnowledgeStatePatch patch,
                                                   List<Long> sourceEvidenceIds) {
        String domain = bookCategoryService.normalize(patch.domain());
        String topic = patch.topic().trim();
        UserKnowledgeState state = repository.findByUserIdAndDomainAndTopic(userId, domain, topic)
            .orElseGet(() -> {
                UserKnowledgeState created = new UserKnowledgeState();
                created.setUserId(userId);
                created.setDomain(domain);
                created.setTopic(topic);
                created.setKnowledgeType("other");
                created.setLevel("unknown");
                created.setConfidence(0.5d);
                created.setSourceEvidenceIds("[]");
                return created;
            });

        state.setKnowledgeType(normalizeKnowledgeType(patch.knowledgeType()));
        state.setLevel(normalizeLevel(patch.level()));
        state.setConfidence(applyConfidenceDelta(state.getConfidence(), patch.confidenceDelta()));
        if (patch.masteredEvidence() != null) {
            state.setMasteredEvidence(patch.masteredEvidence().trim());
        }
        if (patch.weaknessEvidence() != null) {
            state.setWeaknessEvidence(patch.weaknessEvidence().trim());
        }
        if (patch.summary() != null && !patch.summary().isBlank()) {
            state.setSummary(patch.summary().trim());
        }
        state.setRelatedBookId(patch.relatedBookId());
        state.setRelatedBookTitle(blankToNull(patch.relatedBookTitle()));
        state.setRelatedChapterIndex(patch.relatedChapterIndex());
        if (sourceEvidenceIds != null && !sourceEvidenceIds.isEmpty()) {
            state.setSourceEvidenceIds(json.writeObject(sourceEvidenceIds));
        }
        return repository.save(state);
    }

    public boolean isAllowedLevel(String value) {
        return value != null && LEVELS.contains(value);
    }

    public boolean isAllowedKnowledgeType(String value) {
        return value != null && KNOWLEDGE_TYPES.contains(value);
    }

    private String normalizeLevel(String value) {
        return isAllowedLevel(value) ? value : "unknown";
    }

    private String normalizeKnowledgeType(String value) {
        return isAllowedKnowledgeType(value) ? value : "other";
    }

    private double applyConfidenceDelta(Double current, Double delta) {
        double base = current == null ? 0.5d : current;
        double safeDelta = delta == null ? 0d : Math.max(-0.2d, Math.min(0.2d, delta));
        return Math.max(0d, Math.min(1d, base + safeDelta));
    }

    private String blankToNull(String value) {
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return null;
    }
}
