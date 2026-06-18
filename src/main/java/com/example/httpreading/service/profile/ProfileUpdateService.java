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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

        UserStyleProfile oldStyle = styleProfileService.findByUserId(userId).orElse(null);
        List<ReadingUnderstandingProfile> oldReadings = readingProfileService.listByUser(userId);
        String oldStyleSnapshot = profileJson.writeObject(profileMapper.toDto(oldStyle));
        String oldReadingSnapshot = profileJson.writeObject(oldReadings.stream().map(profileMapper::toDto).toList());
        String resolvedCategory = bookCategoryService.resolve(request == null ? null : request.bookId(),
            request == null ? null : request.bookCategory());

        String patchPrompt = buildPatchPrompt(memories, oldStyleSnapshot, oldReadingSnapshot, resolvedCategory, request);
        String rawPatch = modelClient.chat(patchPrompt);
        ParsedProfilePatch parsedPatch = parsePatchWithRetry(rawPatch, patchPrompt, resolvedCategory);
        if (parsedPatch == null) {
            return new ProfileUpdateResponse(
                "profile_patch_parse_failed",
                userId,
                "模型画像 patch 解析失败，未写入数据库。",
                List.of(),
                List.of("profile_patch_parse_failed"),
                memories.size());
        }
        ProfileUpdatePatch patch = parsedPatch.patch();

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

        List<String> warnings = new ArrayList<>();
        if (newStyle != null && !vectorIndexService.upsertStyleStateVector(newStyle)) {
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
        updateLog.setUpdatePatch(parsedPatch.rawJson());
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
                if (item.relatedBookId() != null) {
                    evidence.setRelatedBookId(item.relatedBookId());
                }
                if (item.relatedChapterIndex() != null) {
                    evidence.setRelatedChapterIndex(item.relatedChapterIndex());
                }
                if (item.relatedBookTitle() != null && !item.relatedBookTitle().isBlank()) {
                    evidence.setRelatedBookTitle(item.relatedBookTitle());
                } else if (request.bookId() != null) {
                    booksRepository.findById(request.bookId()).map(Books::getTitle).ifPresent(evidence::setRelatedBookTitle);
                }
            }
            saved.add(evidenceService.saveEvidence(evidence));
        }
        return saved;
    }

    private ParsedProfilePatch parsePatchWithRetry(String rawPatch, String originalPrompt, String fallbackCategory) {
        try {
            return parseAndValidatePatch(rawPatch, fallbackCategory);
        } catch (Exception exception) {
            String firstError = exception.getMessage();
            log.warn("画像 patch 首次解析失败，准备重试修复: {}", firstError);
            String retryRaw = modelClient.chat(buildRetryPrompt(originalPrompt, rawPatch, firstError));
            try {
                return parseAndValidatePatch(retryRaw, fallbackCategory);
            } catch (Exception retryException) {
                log.warn("画像 patch 重试解析仍失败: {}", retryException.getMessage());
                return null;
            }
        }
    }

    private ParsedProfilePatch parseAndValidatePatch(String rawPatch, String fallbackCategory) throws Exception {
        String normalized = extractJson(rawPatch);
        JsonNode root = objectMapper.readTree(normalized);
        validateRoot(root);
        ProfileUpdatePatch patch = objectMapper.treeToValue(root, ProfileUpdatePatch.class);

        List<ReadingProfilePatch> readings = new ArrayList<>();
        for (ReadingProfilePatch reading : patch.readingPatches() == null ? List.<ReadingProfilePatch>of() : patch.readingPatches()) {
            if (!bookCategoryService.isAllowed(reading.bookCategory())) {
                throw new IllegalArgumentException("readingPatches.bookCategory 必须属于固定枚举: " + reading.bookCategory());
            }
            readings.add(new ReadingProfilePatch(
                bookCategoryService.normalize(reading.bookCategory()),
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
                evidence.content(), evidence.importance(), evidence.relatedBookId(),
                evidence.relatedBookTitle(), evidence.relatedChapterIndex()));
        }
        return new ParsedProfilePatch(
            new ProfileUpdatePatch(patch.stylePatch(), readings, evidences, patch.summary()),
            normalized);
    }

    private String buildPatchPrompt(List<MemoryItem> memories,
                                    String oldStyleSnapshot,
                                    String oldReadingSnapshot,
                                    String resolvedCategory,
                                    ProfileUpdateRequest request) {
        return """
            你是用户画像更新器，不是普通问答助手。请根据最近重要情景记忆和旧画像生成局部更新对象。

            强制输出规则：
            - 只输出纯 JSON，不要 Markdown code fence，不要解释文字。
            - 禁止输出 RFC 6902 JSON Patch 格式，不能出现 op/path/value。
            - 顶层只允许 stylePatch、readingPatches、newEvidence、summary 四个字段。

            只允许更新两类画像：style、reading_understanding。
            bookCategory 只能使用：社会学、技术、历史、文学、哲学、心理学、英语、职业成长、经济学、其他。
            当前相关 bookCategory 默认值：%s
            不要生成复杂 domain，不要记录 bookId、chapterIndex、question、answer、currentChapterIndex、readingProgress、readingStatus，不要因为一次记忆大幅改变画像。

            输出 JSON schema 必须是：
            {
              "stylePatch": {
                "explanationStyle": "通俗、具体",
                "preferredDepth": "medium_to_detailed",
                "prefersExamples": true,
                "prefersStorytelling": false,
                "prefersStepByStep": true,
                "avoidance": ["教科书式回答"],
                "summary": "用户偏好通俗、具体、带例子的解释。",
                "confidenceDelta": 0.1
              },
              "readingPatches": [
                {
                  "bookCategory": "%s",
                  "understandingLevel": "learning",
                  "learningStage": "case_mapping",
                  "strengths": ["能识别回答是否空泛"],
                  "weaknesses": ["抽象概念需要具体案例支撑"],
                  "preferredExplanation": ["直接进入故事", "最后回扣原文观点"],
                  "backgroundNeeds": ["历史背景"],
                  "typicalQuestions": ["能否举一个实际例子"],
                  "summary": "用户阅读这类书籍时偏好完整案例和背景解释。",
                  "confidenceDelta": 0.1
                }
              ],
              "newEvidence": [
                {
                  "evidenceDomain": "reading_understanding",
                  "evidenceType": "case_need",
                  "bookCategory": "%s",
                  "content": "用户阅读这类内容时需要完整案例和背景解释。",
                  "importance": 0.82,
                  "relatedBookId": %s,
                  "relatedBookTitle": "",
                  "relatedChapterIndex": %s
                }
              ],
              "summary": "..."
            }

            readingPatches 中禁止出现：op、path、value、bookId、chapterIndex、question、answer、readingProgress、currentChapterIndex。
            newEvidence 必须是对象数组，不允许字符串数组。

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
            resolvedCategory,
            resolvedCategory,
            request == null ? null : request.bookId(),
            request == null ? null : request.chapterIndex(),
            oldStyleSnapshot,
            oldReadingSnapshot,
            request == null ? null : request.bookId(),
            request == null ? null : request.chapterIndex(),
            request == null ? "" : request.question(),
            memories.stream()
                .map(memory -> "- id=" + memory.getId() + ", importance=" + memory.getImportance() + ", content=" + memory.getContent())
                .toList());
    }

    private String buildRetryPrompt(String originalPrompt, String invalidOutput, String parseError) {
        return """
            上一次用户画像更新模型输出不合法，不能写入数据库。请只输出修正后的纯 JSON，不要 Markdown，不要解释。

            解析/校验错误：
            %s

            非法输出：
            %s

            必须遵守：
            - 顶层只允许 stylePatch、readingPatches、newEvidence、summary。
            - 禁止 JSON Patch 格式，不能出现 op/path/value。
            - readingPatches 只能是画像字段对象数组，禁止 bookId/chapterIndex/question/answer/readingProgress/currentChapterIndex。
            - newEvidence 必须是对象数组，不能是字符串数组。
            - bookCategory 只能是：社会学、技术、历史、文学、哲学、心理学、英语、职业成长、经济学、其他。

            目标 schema：
            {
              "stylePatch": null,
              "readingPatches": [
                {
                  "bookCategory": "社会学",
                  "understandingLevel": "learning",
                  "learningStage": "case_mapping",
                  "strengths": ["..."],
                  "weaknesses": ["..."],
                  "preferredExplanation": ["..."],
                  "backgroundNeeds": ["..."],
                  "typicalQuestions": ["..."],
                  "summary": "...",
                  "confidenceDelta": 0.1
                }
              ],
              "newEvidence": [
                {
                  "evidenceDomain": "reading_understanding",
                  "evidenceType": "case_need",
                  "bookCategory": "社会学",
                  "content": "用户阅读社会学类内容时需要完整案例和背景解释。",
                  "importance": 0.82,
                  "relatedBookId": 44,
                  "relatedBookTitle": "",
                  "relatedChapterIndex": 1
                }
              ],
              "summary": "..."
            }

            原始任务提示：
            %s
            """.formatted(parseError, invalidOutput, originalPrompt);
    }

    private String extractJson(String value) {
        if (value == null) {
            return "{}";
        }
        String trimmed = value.trim();
        int fenceStart = trimmed.indexOf("```");
        if (fenceStart >= 0) {
            int contentStart = trimmed.indexOf('\n', fenceStart);
            int fenceEnd = trimmed.indexOf("```", contentStart < 0 ? fenceStart + 3 : contentStart + 1);
            if (contentStart >= 0 && fenceEnd > contentStart) {
                return trimmed.substring(contentStart + 1, fenceEnd).trim();
            }
        }
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1).trim();
        }
        return trimmed;
    }

    private void validateRoot(JsonNode root) {
        if (!(root instanceof ObjectNode objectNode)) {
            throw new IllegalArgumentException("画像 patch 顶层必须是 JSON object");
        }
        List<String> allowedTopLevel = List.of("stylePatch", "readingPatches", "newEvidence", "summary");
        objectNode.fieldNames().forEachRemaining(field -> {
            if (!allowedTopLevel.contains(field)) {
                throw new IllegalArgumentException("画像 patch 顶层字段不允许: " + field);
            }
        });
        JsonNode readingPatches = root.get("readingPatches");
        if (readingPatches != null && !readingPatches.isNull()) {
            if (!(readingPatches instanceof ArrayNode arrayNode)) {
                throw new IllegalArgumentException("readingPatches 必须是对象数组");
            }
            for (JsonNode item : arrayNode) {
                if (!(item instanceof ObjectNode readingObject)) {
                    throw new IllegalArgumentException("readingPatches 必须是对象数组");
                }
                rejectForbiddenReadingFields(readingObject);
                JsonNode category = readingObject.get("bookCategory");
                if (category == null || !category.isTextual() || !bookCategoryService.isAllowed(category.asText())) {
                    throw new IllegalArgumentException("readingPatches.bookCategory 必须属于固定枚举");
                }
            }
        }
        JsonNode newEvidence = root.get("newEvidence");
        if (newEvidence != null && !newEvidence.isNull()) {
            if (!(newEvidence instanceof ArrayNode arrayNode)) {
                throw new IllegalArgumentException("newEvidence 必须是对象数组");
            }
            for (JsonNode item : arrayNode) {
                if (!(item instanceof ObjectNode)) {
                    throw new IllegalArgumentException("newEvidence 必须是对象数组，不允许字符串数组");
                }
            }
        }
    }

    private void rejectForbiddenReadingFields(ObjectNode readingPatch) {
        List<String> forbidden = List.of("op", "path", "value", "bookId", "chapterIndex",
            "question", "answer", "readingProgress", "currentChapterIndex");
        for (String field : forbidden) {
            if (readingPatch.has(field)) {
                throw new IllegalArgumentException("readingPatches 包含禁止字段: " + field);
            }
        }
    }

    private String normalizeEvidenceType(String value) {
        if (value == null || value.isBlank()) {
            return "explanation_preference";
        }
        return value.trim();
    }

    private record ParsedProfilePatch(ProfileUpdatePatch patch, String rawJson) {
    }
}
