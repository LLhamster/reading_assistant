package com.example.httpreading.service.profile;

import com.example.httpreading.domain.profile.UserStyleProfile;
import com.example.httpreading.dto.profile.ProfileDtos.StyleProfilePatch;
import com.example.httpreading.repository.UserStyleProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserStyleProfileService {
    private final UserStyleProfileRepository repository;
    private final ProfileJson json;

    public UserStyleProfileService(UserStyleProfileRepository repository, ProfileJson json) {
        this.repository = repository;
        this.json = json;
    }

    @Transactional
    public UserStyleProfile getOrCreate(String userId) {
        return repository.findByUserId(userId).orElseGet(() -> {
            UserStyleProfile profile = new UserStyleProfile();
            profile.setUserId(userId);
            profile.setAvoidance("[]");
            profile.setConfidence(0.5d);
            return repository.save(profile);
        });
    }

    @Transactional
    public UserStyleProfile updateStyleProfile(String userId, StyleProfilePatch patch) {
        UserStyleProfile profile = getOrCreate(userId);
        if (patch == null) {
            return profile;
        }
        if (hasText(patch.explanationStyle())) {
            profile.setExplanationStyle(patch.explanationStyle().trim());
        }
        if (hasText(patch.preferredDepth())) {
            profile.setPreferredDepth(patch.preferredDepth().trim());
        }
        if (patch.prefersExamples() != null) {
            profile.setPrefersExamples(patch.prefersExamples());
        }
        if (patch.prefersStorytelling() != null) {
            profile.setPrefersStorytelling(patch.prefersStorytelling());
        }
        if (patch.prefersStepByStep() != null) {
            profile.setPrefersStepByStep(patch.prefersStepByStep());
        }
        if (patch.avoidance() != null) {
            profile.setAvoidance(json.writeStringList(patch.avoidance()));
        }
        if (hasText(patch.summary())) {
            profile.setSummary(patch.summary().trim());
        }
        profile.setConfidence(applyConfidenceDelta(profile.getConfidence(), patch.confidenceDelta()));
        return repository.save(profile);
    }

    private double applyConfidenceDelta(Double current, Double delta) {
        double base = current == null ? 0.5d : current;
        double safeDelta = delta == null ? 0d : Math.max(-0.2d, Math.min(0.2d, delta));
        return Math.max(0d, Math.min(1d, base + safeDelta));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
