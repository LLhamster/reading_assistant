package com.example.httpreading.service.profile;

import com.example.httpreading.domain.profile.ReadingUnderstandingProfile;
import com.example.httpreading.domain.profile.UserStyleProfile;
import com.example.httpreading.dto.profile.ProfileDtos.ReadingUnderstandingProfileDto;
import com.example.httpreading.dto.profile.ProfileDtos.StyleProfileDto;
import org.springframework.stereotype.Component;

@Component
public class ProfileMapper {
    private final ProfileJson json;

    public ProfileMapper(ProfileJson json) {
        this.json = json;
    }

    public StyleProfileDto toDto(UserStyleProfile profile) {
        if (profile == null) {
            return null;
        }
        return new StyleProfileDto(
            profile.getId(),
            profile.getUserId(),
            profile.getExplanationStyle(),
            profile.getPreferredDepth(),
            Boolean.TRUE.equals(profile.getPrefersExamples()),
            Boolean.TRUE.equals(profile.getPrefersStorytelling()),
            Boolean.TRUE.equals(profile.getPrefersStepByStep()),
            json.readStringList(profile.getAvoidance()),
            profile.getSummary(),
            profile.getConfidence() == null ? 0.5d : profile.getConfidence(),
            profile.getUpdatedAt());
    }

    public ReadingUnderstandingProfileDto toDto(ReadingUnderstandingProfile profile) {
        if (profile == null) {
            return null;
        }
        return new ReadingUnderstandingProfileDto(
            profile.getId(),
            profile.getUserId(),
            profile.getBookCategory(),
            profile.getUnderstandingLevel(),
            profile.getLearningStage(),
            json.readStringList(profile.getStrengths()),
            json.readStringList(profile.getWeaknesses()),
            json.readStringList(profile.getPreferredExplanation()),
            json.readStringList(profile.getBackgroundNeeds()),
            json.readStringList(profile.getTypicalQuestions()),
            profile.getSummary(),
            profile.getConfidence() == null ? 0.5d : profile.getConfidence(),
            profile.getLastEvidenceId(),
            profile.getEvidenceCount() == null ? 0 : profile.getEvidenceCount(),
            profile.getUpdatedAt());
    }
}
