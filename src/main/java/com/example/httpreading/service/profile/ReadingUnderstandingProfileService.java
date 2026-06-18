package com.example.httpreading.service.profile;

import java.util.List;

import com.example.httpreading.domain.profile.ReadingUnderstandingProfile;
import com.example.httpreading.dto.profile.ProfileDtos.ReadingProfilePatch;
import com.example.httpreading.repository.ReadingUnderstandingProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReadingUnderstandingProfileService {
    private final ReadingUnderstandingProfileRepository repository;
    private final ProfileJson json;
    private final BookCategoryService bookCategoryService;

    public ReadingUnderstandingProfileService(ReadingUnderstandingProfileRepository repository,
                                             ProfileJson json,
                                             BookCategoryService bookCategoryService) {
        this.repository = repository;
        this.json = json;
        this.bookCategoryService = bookCategoryService;
    }

    @Transactional
    public ReadingUnderstandingProfile getOrCreate(String userId, String bookCategory) {
        String category = bookCategoryService.normalize(bookCategory);
        return repository.findByUserIdAndBookCategory(userId, category).orElseGet(() -> {
            ReadingUnderstandingProfile profile = new ReadingUnderstandingProfile();
            profile.setUserId(userId);
            profile.setBookCategory(category);
            profile.setUnderstandingLevel("unknown");
            profile.setLearningStage("concept_recognition");
            profile.setStrengths("[]");
            profile.setWeaknesses("[]");
            profile.setPreferredExplanation("[]");
            profile.setBackgroundNeeds("[]");
            profile.setTypicalQuestions("[]");
            profile.setConfidence(0.5d);
            profile.setEvidenceCount(0);
            return repository.save(profile);
        });
    }

    public List<ReadingUnderstandingProfile> listByUser(String userId) {
        return repository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    @Transactional
    public ReadingUnderstandingProfile updateReadingProfile(String userId,
                                                           String bookCategory,
                                                           ReadingProfilePatch patch,
                                                           Long evidenceId) {
        String category = bookCategoryService.normalize(bookCategory);
        ReadingUnderstandingProfile profile = getOrCreate(userId, category);
        if (patch == null) {
            return profile;
        }
        if (isAllowedUnderstandingLevel(patch.understandingLevel())) {
            profile.setUnderstandingLevel(patch.understandingLevel().trim());
        }
        if (isAllowedLearningStage(patch.learningStage())) {
            profile.setLearningStage(patch.learningStage().trim());
        }
        if (patch.strengths() != null) {
            profile.setStrengths(json.writeStringList(patch.strengths()));
        }
        if (patch.weaknesses() != null) {
            profile.setWeaknesses(json.writeStringList(patch.weaknesses()));
        }
        if (patch.preferredExplanation() != null) {
            profile.setPreferredExplanation(json.writeStringList(patch.preferredExplanation()));
        }
        if (patch.backgroundNeeds() != null) {
            profile.setBackgroundNeeds(json.writeStringList(patch.backgroundNeeds()));
        }
        if (patch.typicalQuestions() != null) {
            profile.setTypicalQuestions(json.writeStringList(patch.typicalQuestions()));
        }
        if (patch.summary() != null && !patch.summary().isBlank()) {
            profile.setSummary(patch.summary().trim());
        }
        profile.setConfidence(applyConfidenceDelta(profile.getConfidence(), patch.confidenceDelta()));
        if (evidenceId != null) {
            profile.setLastEvidenceId(evidenceId);
        }
        profile.setEvidenceCount((profile.getEvidenceCount() == null ? 0 : profile.getEvidenceCount()) + 1);
        return repository.save(profile);
    }

    private boolean isAllowedUnderstandingLevel(String value) {
        return List.of("unknown", "beginner", "learning", "basic_understood", "well_understood", "advanced")
            .contains(value);
    }

    private boolean isAllowedLearningStage(String value) {
        return List.of("concept_recognition", "concept_understanding", "case_mapping",
            "structure_building", "critical_thinking", "application").contains(value);
    }

    private double applyConfidenceDelta(Double current, Double delta) {
        double base = current == null ? 0.5d : current;
        double safeDelta = delta == null ? 0d : Math.max(-0.2d, Math.min(0.2d, delta));
        return Math.max(0d, Math.min(1d, base + safeDelta));
    }
}
