package com.example.httpreading.service.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.httpreading.domain.entity.Books;
import com.example.httpreading.domain.profile.ProfileGrowthEvidence;
import com.example.httpreading.domain.profile.ProfileUpdateLog;
import com.example.httpreading.domain.profile.ReadingUnderstandingProfile;
import com.example.httpreading.domain.profile.UserStyleProfile;
import com.example.httpreading.dto.profile.ProfileDtos.NewEvidencePatch;
import com.example.httpreading.dto.profile.ProfileDtos.ProfileOverviewResponse;
import com.example.httpreading.dto.profile.ProfileDtos.ProfileUpdatePatch;
import com.example.httpreading.dto.profile.ProfileDtos.ProfileUpdateRequest;
import com.example.httpreading.dto.profile.ProfileDtos.ProfileUpdateResponse;
import com.example.httpreading.dto.profile.ProfileDtos.ReadingProfilePatch;
import com.example.httpreading.memory.model.MemoryItem;
import com.example.httpreading.repository.BooksRepository;
import com.example.httpreading.repository.ProfileUpdateLogRepository;
import com.example.httpreading.service.AgentMemoryService;
import com.example.httpreading.service.ModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileUpdateService {
    private static final Logger log = LoggerFactory.getLogger(ProfileUpdateService.class);
    private static final int DEFAULT_MEMORY_LIMIT = 30;
    private static final int MIN_MEMORY_COUNT = 5;
    private static final double MIN_IMPORTANCE = 0.65d;

    private final ProfileUserResolver userResolver;
    private final AgentMemoryService agentMemoryService;
    private final UserStyleProfileService styleProfileService;
    private final ReadingUnderstandingProfileService readingProfileService;
    private final ProfileGrowthEvidenceService evidenceService;
    private final ProfileVectorIndexService vectorIndexService;
    private final ProfileUpdateLogRepository updateLogRepository;
    private final BookCategoryService bookCategoryService;
    private final BooksRepository booksRepository;
    private final ModelClient modelClient;
    private final ObjectMapper objectMapper;
    private final ProfileJson profileJson;
    private final ProfileMapper profileMapper;

    public ProfileUpdateService(ProfileUserResolver userResolver,
                                AgentMemoryService agentMemoryService,
                                UserStyleProfileService styleProfileService,
                                ReadingUnderstandingProfileService readingProfileService,
                                ProfileGrowthEvidenceService evidenceService,
                                ProfileVectorIndexService vectorIndexService,
                                ProfileUpdateLogRepository updateLogRepository,
                                BookCategoryService bookCategoryService,
                                BooksRepository booksRepository,
                                ModelClient modelClient,
                                ObjectMapper objectMapper,
                                ProfileJson profileJson,
                                ProfileMapper profileMapper) {
        this.userResolver = userResolver;
        this.agentMemoryService = agentMemoryService;
        this.styleProfileService = styleProfileService;
        this.readingProfileService = readingProfileService;
        this.evidenceService = evidenceService;
        this.vectorIndexService = vectorIndexService;
        this.updateLogRepository = updateLogRepository;
        this.bookCategoryService = bookCategoryService;
        this.booksRepository = booksRepository;
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
        this.profileJson = profileJson;
        this.profileMapper = profileMapper;
    }

    @Transactional
    public ProfileUpdateResponse updateProfileManually(ProfileUpdateRequest request) {
        String userId = userResolver.resolve(request == null ? null : request.userId(),
            request == null ? null : request.sessionId());
        List<MemoryItem> memories = agentMemoryService.recentImportantEpisodic(userId, DEFAULT_MEMORY_LIMIT, MIN_IMPORTANCE);
        if (memories.size() < MIN_MEMORY_COUNT) {
            return new ProfileUpdateResponse(
                "not_enough_memory",
                userId,
                "最近重要记忆不足，继续阅读问答后再更新画像。",
                List.of(),
                List.of("not_enough_memory"),
                memories.size());
        }

        UserStyleProfile oldStyle = styleProfileService.getOrCreate(userId);
        List<ReadingUnderstandingProfile> oldReadings = readingProfileService.listByUser(userId);
        String oldStyleSnapshot = profileJson.writeObject(profileMapper.toDto(oldStyle));
        String oldReadingSnapshot = profileJson.writeObject(oldReadings.stream().map(profileMapper::toDto).toList());
        String resolvedCategory = bookCategoryService.resolve(request == null ? null : request.bookId(),
            request == null ? null : request.bookCategory());

        String rawPatch = modelClient.chat(buildPatchPrompt(memories, oldStyleSnapshot, oldReadingSnapshot, resolvedCategory, request));
        ProfileUpdatePatch patch = parseAndValidatePatch(rawPatch, resolvedCategory);

        List<ProfileGrowthEvidence> savedEvidence = saveEvidence(userId, patch, request);
        Map<String, Long> evidenceByCategory = new LinkedHashMap<>();
        Long styleEvidenceId = null;
        for (ProfileGrowthEvidence evidence : savedEvidence) {
            if ("style".equals(evidence.getEvidenceDomain()) && styleEvidenceId == null) {
                styleEvidenceId = evidence.getId();
            }
            if ("reading_understanding".equals(evidence.getEvidenceDomain())) {
                evidenceByCategory.put(evidence.getBookCategory(), evidence.getId());
            }
        }

        UserStyleProfile newStyle = styleProfileService.updateStyleProfile(userId, patch.stylePatch());
        List<ReadingUnderstandingProfile> changedReadings = new ArrayList<>();
        for (ReadingProfilePatch readingPatch : patch.readingPatches() == null ? List.<ReadingProfilePatch>of() : patch.readingPatches()) {
            String category = bookCategoryService.normalize(readingPatch.bookCategory());
            changedReadings.add(readingProfileService.updateReadingProfile(
                userId,
                category,
                readingPatch,
                evidenceByCategory.get(category)));
        }

        List<String> warnings = new ArrayList<>();
        if (!vectorIndexService.upsertStyleStateVector(newStyle)) {
            warnings.add("sync_failed:style");
        }
        for (ReadingUnderstandingProfile reading : changedReadings) {
            if (!vectorIndexService.upsertReadingStateVector(reading)) {
                warnings.add("sync_failed:reading:" + reading.getBookCategory());
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
        updateLog.setUpdatePatch(rawPatch);
        updateLog.setUsedMemoryIds(profileJson.writeObject(memories.stream().map(MemoryItem::getId).toList()));
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
            memories.size());
    }

    public ProfileOverviewResponse overview(String requestedUserId, String sessionId) {
        String userId = userResolver.resolve(requestedUserId, sessionId);
        return new ProfileOverviewResponse(
            userId,
            profileMapper.toDto(styleProfileService.getOrCreate(userId)),
            readingProfileService.listByUser(userId).stream().map(profileMapper::toDto).toList());
    }

    private List<ProfileGrowthEvidence> saveEvidence(String userId,
                                                     ProfileUpdatePatch patch,
                                                     ProfileUpdateRequest request) {
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
            if (request != null) {
                evidence.setRelatedBookId(request.bookId());
                evidence.setRelatedChapterIndex(request.chapterIndex());
                if (request.bookId() != null) {
                    booksRepository.findById(request.bookId()).map(Books::getTitle).ifPresent(evidence::setRelatedBookTitle);
                }
            }
            saved.add(evidenceService.saveEvidence(evidence));
        }
        return saved;
    }

    private ProfileUpdatePatch parseAndValidatePatch(String rawPatch, String fallbackCategory) {
        String normalized = stripCodeFence(rawPatch);
        if (containsForbiddenField(normalized)) {
            throw new IllegalArgumentException("画像 patch 包含禁止的阅读进度字段");
        }
        try {
            ProfileUpdatePatch patch = objectMapper.readValue(normalized, ProfileUpdatePatch.class);
            List<ReadingProfilePatch> readings = new ArrayList<>();
            for (ReadingProfilePatch reading : patch.readingPatches() == null ? List.<ReadingProfilePatch>of() : patch.readingPatches()) {
                String category = bookCategoryService.normalize(reading.bookCategory());
                if (!bookCategoryService.isAllowed(reading.bookCategory())) {
                    category = fallbackCategory;
                }
                readings.add(new ReadingProfilePatch(
                    category,
                    reading.understandingLevel(),
                    reading.learningStage(),
                    reading.strengths(),
                    reading.weaknesses(),
                    reading.preferredExplanation(),
                    reading.backgroundNeeds(),
                    reading.typicalQuestions(),
                    reading.summary(),
                    reading.confidenceDelta()));
            }
            List<NewEvidencePatch> evidences = new ArrayList<>();
            for (NewEvidencePatch evidence : patch.newEvidence() == null ? List.<NewEvidencePatch>of() : patch.newEvidence()) {
                String domain = "style".equals(evidence.evidenceDomain()) ? "style" : "reading_understanding";
                String category = "reading_understanding".equals(domain)
                    ? bookCategoryService.normalize(evidence.bookCategory())
                    : null;
                evidences.add(new NewEvidencePatch(domain, evidence.evidenceType(), category,
                    evidence.content(), evidence.importance()));
            }
            return new ProfileUpdatePatch(patch.stylePatch(), readings, evidences, patch.summary());
        } catch (Exception exception) {
            log.warn("画像 patch 解析失败: {}", exception.getMessage());
            throw new IllegalArgumentException("模型画像 patch 解析失败", exception);
        }
    }

    private String buildPatchPrompt(List<MemoryItem> memories,
                                    String oldStyleSnapshot,
                                    String oldReadingSnapshot,
                                    String resolvedCategory,
                                    ProfileUpdateRequest request) {
        return """
            你是用户画像更新器，不是普通问答助手。请根据最近重要情景记忆和旧画像生成局部 JSON patch。

            只允许更新两类画像：style、reading_understanding。
            bookCategory 只能使用：社会学、技术、历史、文学、哲学、心理学、英语、职业成长、经济学、其他。
            当前相关 bookCategory 默认值：%s
            不要生成复杂 domain，不要记录 currentChapterIndex、readingProgress、readingStatus，不要因为一次记忆大幅改变画像。

            输出 JSON，字段必须是：
            {
              "stylePatch": {...或null},
              "readingPatches": [],
              "newEvidence": [],
              "summary": "..."
            }

            旧风格画像：
            %s

            旧阅读理解画像：
            %s

            当前请求信息：
            bookId=%s, chapterIndex=%s, question=%s

            最近重要情景记忆：
            %s
            """.formatted(
            resolvedCategory,
            oldStyleSnapshot,
            oldReadingSnapshot,
            request == null ? null : request.bookId(),
            request == null ? null : request.chapterIndex(),
            request == null ? "" : request.question(),
            memories.stream()
                .map(memory -> "- id=" + memory.getId() + ", importance=" + memory.getImportance() + ", content=" + memory.getContent())
                .toList());
    }

    private boolean containsForbiddenField(String raw) {
        String text = raw == null ? "" : raw;
        return text.contains("currentChapterIndex")
            || text.contains("readingProgress")
            || text.contains("readingStatus");
    }

    private String stripCodeFence(String value) {
        if (value == null) {
            return "{}";
        }
        return value.replace("```json", "").replace("```", "").trim();
    }

    private String normalizeEvidenceType(String value) {
        if (value == null || value.isBlank()) {
            return "explanation_preference";
        }
        return value.trim();
    }
}
