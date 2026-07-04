package com.example.httpreading.service.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import com.example.httpreading.domain.profile.ProfileGrowthEvidence;
import com.example.httpreading.domain.profile.ProfileUpdateLog;
import com.example.httpreading.domain.profile.ReadingUnderstandingProfile;
import com.example.httpreading.domain.profile.UserKnowledgeState;
import com.example.httpreading.domain.profile.UserStyleProfile;
import com.example.httpreading.dto.profile.ProfileDtos.KnowledgeStatePatch;
import com.example.httpreading.dto.profile.ProfileDtos.ManualStyleProfileRequest;
import com.example.httpreading.dto.profile.ProfileDtos.ManualStyleProfileResponse;
import com.example.httpreading.dto.profile.ProfileDtos.NewEvidencePatch;
import com.example.httpreading.dto.profile.ProfileDtos.ProfileOverviewResponse;
import com.example.httpreading.dto.profile.ProfileDtos.ProfileUpdatePatch;
import com.example.httpreading.dto.profile.ProfileDtos.ProfileUpdateRequest;
import com.example.httpreading.dto.profile.ProfileDtos.ProfileUpdateResponse;
import com.example.httpreading.dto.profile.ProfileDtos.ReadingProfilePatch;
import com.example.httpreading.dto.profile.ProfileDtos.StyleProfilePatch;
import com.example.httpreading.memory.model.MemoryItem;
import com.example.httpreading.repository.ProfileUpdateLogRepository;
import com.example.httpreading.service.AgentMemoryService;
import com.example.httpreading.service.profile.ProfilePatchExtractor.ExtractedProfilePatch;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileUpdateService {
    private static final int DEFAULT_MEMORY_LIMIT = 30;
    private static final int MIN_MEMORY_COUNT = 5;
    private static final double MIN_IMPORTANCE = 0.65d;

    private final ProfileUserResolver userResolver;
    private final AgentMemoryService agentMemoryService;
    private final UserStyleProfileService styleProfileService;
    private final ReadingUnderstandingProfileService readingProfileService;
    private final UserKnowledgeStateService knowledgeStateService;
    private final ProfileGrowthEvidenceService evidenceService;
    private final ProfileVectorIndexService vectorIndexService;
    private final ProfileUpdateLogRepository updateLogRepository;
    private final BookCategoryService bookCategoryService;
    private final ProfilePatchExtractor patchExtractor;
    private final ProfileJson profileJson;
    private final ProfileMapper profileMapper;

    public ProfileUpdateService(ProfileUserResolver userResolver,
                                AgentMemoryService agentMemoryService,
                                UserStyleProfileService styleProfileService,
                                ReadingUnderstandingProfileService readingProfileService,
                                UserKnowledgeStateService knowledgeStateService,
                                ProfileGrowthEvidenceService evidenceService,
                                ProfileVectorIndexService vectorIndexService,
                                ProfileUpdateLogRepository updateLogRepository,
                                BookCategoryService bookCategoryService,
                                ProfilePatchExtractor patchExtractor,
                                ProfileJson profileJson,
                                ProfileMapper profileMapper) {
        this.userResolver = userResolver;
        this.agentMemoryService = agentMemoryService;
        this.styleProfileService = styleProfileService;
        this.readingProfileService = readingProfileService;
        this.knowledgeStateService = knowledgeStateService;
        this.evidenceService = evidenceService;
        this.vectorIndexService = vectorIndexService;
        this.updateLogRepository = updateLogRepository;
        this.bookCategoryService = bookCategoryService;
        this.patchExtractor = patchExtractor;
        this.profileJson = profileJson;
        this.profileMapper = profileMapper;
    }

    @Transactional
    public ProfileUpdateResponse updateProfileManually(ProfileUpdateRequest request) {
        String userId = userResolver.resolve(request == null ? null : request.userId(),
            request == null ? null : request.sessionId());
        List<MemoryItem> memories = agentMemoryService.recentImportantEpisodic(userId, DEFAULT_MEMORY_LIMIT, MIN_IMPORTANCE);
        List<ProfileGrowthEvidence> readingNotes = evidenceService.recentReadingNotes(userId, DEFAULT_MEMORY_LIMIT);
        List<MemoryItem> profileSources = new ArrayList<>(memories);
        profileSources.addAll(readingNotes.stream().map(this::readingNoteSource).toList());
        if (memories.size() < MIN_MEMORY_COUNT && readingNotes.isEmpty()) {
            return new ProfileUpdateResponse(
                "not_enough_memory",
                userId,
                "最近重要记忆和阅读笔记不足，继续阅读问答或记录笔记后再更新画像。",
                List.of(),
                List.of("not_enough_memory"),
                profileSources.size());
        }

        UserStyleProfile oldStyle = styleProfileService.findByUserId(userId).orElse(null);
        List<ReadingUnderstandingProfile> oldReadings = readingProfileService.listByUser(userId);
        List<UserKnowledgeState> oldKnowledgeStates = knowledgeStateService.listByUser(userId);
        String oldStyleSnapshot = profileJson.writeObject(profileMapper.toDto(oldStyle));
        String oldReadingSnapshot = profileJson.writeObject(oldReadings.stream().map(profileMapper::toDto).toList());
        String resolvedCategory = bookCategoryService.normalize(request == null ? null : request.bookCategory());

        ExtractedProfilePatch extractedPatch = patchExtractor.extract(
            profileSources,
            profileMapper.toDto(oldStyle),
            oldReadings.stream().map(profileMapper::toDto).toList(),
            oldKnowledgeStates.stream().map(profileMapper::toDto).toList(),
            resolvedCategory,
            request == null ? "" : request.question());
        if (extractedPatch == null) {
            return new ProfileUpdateResponse(
                "profile_patch_parse_failed",
                userId,
                "模型画像 patch 解析失败，未写入数据库。",
                List.of(),
                List.of("profile_patch_parse_failed"),
                profileSources.size());
        }
        ProfileUpdatePatch patch = extractedPatch.patch();

        List<ProfileGrowthEvidence> savedEvidence = saveEvidence(userId, patch);
        Map<String, Long> evidenceByCategory = new LinkedHashMap<>();
        for (ProfileGrowthEvidence evidence : savedEvidence) {
            if ("reading_understanding".equals(evidence.getEvidenceDomain())) {
                evidenceByCategory.put(evidence.getBookCategory(), evidence.getId());
            }
        }

        UserStyleProfile newStyle = oldStyle;
        if (patch.stylePatch() != null) {
            newStyle = styleProfileService.updateStyleProfile(userId, patch.stylePatch());
        }
        List<ReadingUnderstandingProfile> changedReadings = new ArrayList<>();
        for (ReadingProfilePatch readingPatch : patch.readingPatches() == null ? List.<ReadingProfilePatch>of() : patch.readingPatches()) {
            String category = bookCategoryService.normalize(readingPatch.bookCategory());
            changedReadings.add(readingProfileService.updateReadingProfile(
                userId,
                category,
                readingPatch,
                evidenceByCategory.get(category)));
        }
        List<Long> sourceEvidenceIds = savedEvidence.stream().map(ProfileGrowthEvidence::getId).toList();
        List<UserKnowledgeState> changedKnowledgeStates = new ArrayList<>();
        for (KnowledgeStatePatch knowledgePatch : patch.knowledgePatches() == null ? List.<KnowledgeStatePatch>of() : patch.knowledgePatches()) {
            changedKnowledgeStates.add(knowledgeStateService.updateKnowledgeState(
                userId,
                knowledgePatch,
                sourceEvidenceIds));
        }

        List<String> warnings = new ArrayList<>();
        if (newStyle != null && !vectorIndexService.upsertStyleStateVector(newStyle)) {
            warnings.add("sync_failed:style");
        }
        for (ReadingUnderstandingProfile reading : changedReadings) {
            if (!vectorIndexService.upsertReadingStateVector(reading)) {
                warnings.add("sync_failed:reading:" + reading.getBookCategory());
            }
        }
        for (UserKnowledgeState knowledgeState : changedKnowledgeStates) {
            if (!vectorIndexService.upsertKnowledgeStateVector(knowledgeState)) {
                warnings.add("sync_failed:knowledge:" + knowledgeState.getDomain() + ":" + knowledgeState.getTopic());
            }
        }
        for (ProfileGrowthEvidence evidence : savedEvidence) {
            if (!vectorIndexService.upsertEvidenceVector(evidence)) {
                warnings.add("sync_failed:evidence:" + evidence.getId());
            }
        }

        ProfileUpdateLog updateLog = new ProfileUpdateLog();
        updateLog.setUserId(userId);
        updateLog.setOldStyleSnapshot(oldStyleSnapshot);
        updateLog.setOldReadingSnapshot(oldReadingSnapshot);
        updateLog.setNewStyleSnapshot(profileJson.writeObject(profileMapper.toDto(newStyle)));
        updateLog.setNewReadingSnapshot(profileJson.writeObject(readingProfileService.listByUser(userId).stream().map(profileMapper::toDto).toList()));
        updateLog.setUpdatePatch(extractedPatch.rawJson());
        updateLog.setUsedMemoryIds(profileJson.writeObject(profileSources.stream().map(MemoryItem::getId).toList()));
        updateLog.setUsedEvidenceIds(profileJson.writeObject(savedEvidence.stream().map(ProfileGrowthEvidence::getId).toList()));
        updateLog.setUpdateReason(patch.summary());
        updateLogRepository.save(updateLog);

        List<String> changes = new ArrayList<>();
        if (patch.stylePatch() != null) {
            changes.add("更新用户个人风格画像");
        }
        for (ReadingUnderstandingProfile reading : changedReadings) {
            changes.add("更新" + reading.getBookCategory() + "类阅读理解画像");
        }
        for (UserKnowledgeState knowledgeState : changedKnowledgeStates) {
            changes.add("更新知识点掌握状态：" + knowledgeState.getDomain() + " / " + knowledgeState.getTopic());
        }
        if (changes.isEmpty()) {
            changes.add("本次没有可应用的画像变化");
        }
        String status = warnings.isEmpty() ? "success" : "success_with_warning";
        return new ProfileUpdateResponse(
            status,
            userId,
            patch.summary() == null || patch.summary().isBlank() ? "用户画像更新完成" : patch.summary(),
            changes,
            warnings,
            profileSources.size());
    }

    public ProfileOverviewResponse overview(String requestedUserId, String sessionId) {
        String userId = userResolver.resolve(requestedUserId, sessionId);
        return new ProfileOverviewResponse(
            userId,
            profileMapper.toDto(styleProfileService.getOrCreate(userId)),
            readingProfileService.listByUser(userId).stream().map(profileMapper::toDto).toList(),
            knowledgeStateService.listByUser(userId).stream().map(profileMapper::toDto).toList());
    }

    @Transactional
    public ManualStyleProfileResponse updateStyleManually(ManualStyleProfileRequest request) {
        String userId = userResolver.resolve(request == null ? null : request.userId(),
            request == null ? null : request.sessionId());
        UserStyleProfile styleProfile = styleProfileService.replaceStyleProfile(userId, new StyleProfilePatch(
            request == null ? null : request.explanationStyle(),
            request == null ? null : request.preferredDepth(),
            request != null && request.prefersExamples() != null ? request.prefersExamples() : false,
            request != null && request.prefersStorytelling() != null ? request.prefersStorytelling() : false,
            request != null && request.prefersStepByStep() != null ? request.prefersStepByStep() : false,
            request == null ? List.of() : request.avoidance(),
            request == null ? null : request.summary(),
            null));
        List<String> warnings = new ArrayList<>();
        if (!vectorIndexService.upsertStyleStateVector(styleProfile)) {
            warnings.add("sync_failed:style");
        }
        return new ManualStyleProfileResponse(
            warnings.isEmpty() ? "success" : "success_with_warning",
            userId,
            profileMapper.toDto(styleProfile),
            warnings);
    }

    private List<ProfileGrowthEvidence> saveEvidence(String userId, ProfileUpdatePatch patch) {
        List<ProfileGrowthEvidence> saved = new ArrayList<>();
        for (NewEvidencePatch item : patch.newEvidence() == null ? List.<NewEvidencePatch>of() : patch.newEvidence()) {
            if (item.content() == null || item.content().isBlank()) {
                continue;
            }
            ProfileGrowthEvidence evidence = new ProfileGrowthEvidence();
            evidence.setUserId(userId);
            evidence.setEvidenceDomain("style".equals(item.evidenceDomain()) ? "style" : "reading_understanding");
            evidence.setEvidenceType(normalizeEvidenceType(item.evidenceType()));
            evidence.setBookCategory("reading_understanding".equals(evidence.getEvidenceDomain())
                ? bookCategoryService.normalize(item.bookCategory())
                : null);
            evidence.setContent(item.content().trim());
            evidence.setImportance(item.importance() == null ? 0.5d : Math.max(0d, Math.min(1d, item.importance())));
            if (item.relatedBookId() != null) {
                evidence.setRelatedBookId(item.relatedBookId());
            }
            if (item.relatedChapterIndex() != null) {
                evidence.setRelatedChapterIndex(item.relatedChapterIndex());
            }
            if (item.relatedBookTitle() != null && !item.relatedBookTitle().isBlank()) {
                evidence.setRelatedBookTitle(item.relatedBookTitle().trim());
            }
            saved.add(evidenceService.saveEvidence(evidence));
        }
        return saved;
    }

    private String normalizeEvidenceType(String value) {
        if (value == null || value.isBlank()) {
            return "explanation_preference";
        }
        return value.trim();
    }

    private MemoryItem readingNoteSource(ProfileGrowthEvidence evidence) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sourceType", "reading_note");
        metadata.put("existingEvidenceId", evidence.getId());
        metadata.put("bookId", evidence.getRelatedBookId());
        metadata.put("bookTitle", evidence.getRelatedBookTitle());
        metadata.put("chapterIndex", evidence.getRelatedChapterIndex());
        metadata.put("annotationId", evidence.getRelatedAnnotationId());
        metadata.put("instruction", "该阅读笔记已保存为画像证据，只用于推导画像，不要在 newEvidence 中重复创建");
        return new MemoryItem(
            "reading_note:" + evidence.getId(),
            evidence.getContent(),
            "reading_note",
            evidence.getUserId(),
            evidence.getUpdatedAt(),
            evidence.getImportance() == null ? 0.75f : evidence.getImportance().floatValue(),
            metadata);
    }

}
