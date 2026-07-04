package com.example.httpreading.service.profile;

import java.util.ArrayList;
import java.util.List;

import com.example.httpreading.dto.profile.ProfileDtos.KnowledgeStatePatch;
import com.example.httpreading.dto.profile.ProfileDtos.NewEvidencePatch;
import com.example.httpreading.dto.profile.ProfileDtos.ProfileUpdatePatch;
import com.example.httpreading.dto.profile.ProfileDtos.ReadingProfilePatch;
import com.example.httpreading.dto.profile.ProfileDtos.ReadingUnderstandingProfileDto;
import com.example.httpreading.dto.profile.ProfileDtos.StyleProfileDto;
import com.example.httpreading.dto.profile.ProfileDtos.UserKnowledgeStateDto;
import com.example.httpreading.memory.model.MemoryItem;
import com.example.httpreading.service.ModelClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ProfilePatchExtractor {
    private static final Logger log = LoggerFactory.getLogger(ProfilePatchExtractor.class);

    private final ModelClient modelClient;
    private final ObjectMapper objectMapper;
    private final ProfileJson profileJson;
    private final BookCategoryService bookCategoryService;
    private final UserKnowledgeStateService knowledgeStateService;

    public ProfilePatchExtractor(ModelClient modelClient,
                                 ObjectMapper objectMapper,
                                 ProfileJson profileJson,
                                 BookCategoryService bookCategoryService,
                                 UserKnowledgeStateService knowledgeStateService) {
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
        this.profileJson = profileJson;
        this.bookCategoryService = bookCategoryService;
        this.knowledgeStateService = knowledgeStateService;
    }

    public ExtractedProfilePatch extract(List<MemoryItem> memories,
                                         StyleProfileDto oldStyle,
                                         List<ReadingUnderstandingProfileDto> oldReadings,
                                         List<UserKnowledgeStateDto> oldKnowledgeStates,
                                         String fallbackCategory,
                                         String question) {
        String prompt = buildPatchPrompt(
            memories,
            profileJson.writeObject(oldStyle),
            profileJson.writeObject(oldReadings == null ? List.of() : oldReadings),
            profileJson.writeObject(oldKnowledgeStates == null ? List.of() : oldKnowledgeStates),
            fallbackCategory,
            question);
        String rawPatch = modelClient.chat(prompt);
        return parsePatchWithRetry(rawPatch, prompt);
    }

    private ExtractedProfilePatch parsePatchWithRetry(String rawPatch, String originalPrompt) {
        try {
            return parseAndValidatePatch(rawPatch);
        } catch (Exception exception) {
            String firstError = exception.getMessage();
            log.warn("画像 patch 首次解析失败，准备重试修复: {}", firstError);
            String retryRaw = modelClient.chat(buildRetryPrompt(originalPrompt, rawPatch, firstError));
            try {
                return parseAndValidatePatch(retryRaw);
            } catch (Exception retryException) {
                log.warn("画像 patch 重试解析仍失败: {}", retryException.getMessage());
                return null;
            }
        }
    }

    private ExtractedProfilePatch parseAndValidatePatch(String rawPatch) throws Exception {
        String normalized = extractJson(rawPatch);
        JsonNode root = objectMapper.readTree(normalized);
        validateRoot(root);
        ProfileUpdatePatch patch = objectMapper.treeToValue(root, ProfileUpdatePatch.class);

        List<ReadingProfilePatch> readings = new ArrayList<>();
        for (ReadingProfilePatch reading : patch.readingPatches() == null ? List.<ReadingProfilePatch>of() : patch.readingPatches()) {
            if (!bookCategoryService.isAllowed(reading.bookCategory())) {
                throw new IllegalArgumentException("readingPatches.bookCategory 必须属于固定枚举: " + reading.bookCategory());
            }
            if (isStylePreferenceReadingPatch(reading)) {
                continue;
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
            if ("reading_understanding".equals(domain) && isStylePreferenceText(evidence.content())) {
                domain = "style";
                category = null;
            }
            evidences.add(new NewEvidencePatch(domain, evidence.evidenceType(), category,
                evidence.content(), evidence.importance(), evidence.relatedBookId(),
                blankToNull(evidence.relatedBookTitle()), evidence.relatedChapterIndex()));
        }
        List<KnowledgeStatePatch> knowledgePatches = new ArrayList<>();
        for (KnowledgeStatePatch knowledge : patch.knowledgePatches() == null ? List.<KnowledgeStatePatch>of() : patch.knowledgePatches()) {
            knowledgePatches.add(new KnowledgeStatePatch(
                bookCategoryService.normalize(knowledge.domain()),
                knowledge.topic().trim(),
                knowledge.knowledgeType(),
                knowledge.level(),
                knowledge.confidenceDelta(),
                knowledge.masteredEvidence(),
                knowledge.weaknessEvidence(),
                knowledge.summary(),
                knowledge.relatedBookId(),
                blankToNull(knowledge.relatedBookTitle()),
                knowledge.relatedChapterIndex()));
        }
        return new ExtractedProfilePatch(
            new ProfileUpdatePatch(patch.stylePatch(), readings, knowledgePatches, evidences, patch.summary()),
            normalized);
    }

    private String buildPatchPrompt(List<MemoryItem> memories,
                                    String oldStyleSnapshot,
                                    String oldReadingSnapshot,
                                    String oldKnowledgeSnapshot,
                                    String fallbackCategory,
                                    String question) {
        String resolvedCategory = bookCategoryService.normalize(fallbackCategory);
        return """
            你是用户画像更新器，不是普通问答助手。请根据最近重要情景记忆、阅读笔记和旧画像生成局部更新对象。

            强制输出规则：
            - 只输出纯 JSON，不要 Markdown code fence，不要解释文字。
            - 禁止输出 RFC 6902 JSON Patch 格式，不能出现 op/path/value。
            - 顶层只允许 stylePatch、readingPatches、knowledgePatches、newEvidence、summary 五个字段。

            只允许更新三类画像：style、reading_understanding、user_knowledge_state。
            bookCategory/domain 只能使用：社会学、技术、历史、文学、哲学、心理学、英语、职业成长、经济学、其他。
            当前相关 bookCategory 默认值：%s
            当前问题只用于理解本次手动更新意图，不能作为 evidence 或 knowledge state 的来源。
            memoryType=reading_note 的项目是已经保存的画像证据，可用于更新 readingPatches 和 knowledgePatches，
            但不要把它们再次复制到 newEvidence。
            不要生成复杂 domain；readingPatches 不要记录 bookId、chapterIndex、question、answer、currentChapterIndex、readingProgress、readingStatus；不要因为一次记忆大幅改变画像。
            不要照抄 schema 示例。没有证据支持的字段必须输出 null 或 []。

            来源字段规则：
            - ProfileGrowthEvidence 和 user_knowledge_state 的 relatedBookId/relatedBookTitle/relatedChapterIndex 只能来自记忆 metadata/properties 中明确的来源。
            - 支持识别 metadata key：bookId、bookTitle、chapterIndex、relatedBookId、relatedBookTitle、relatedChapterIndex。
            - 如果记忆 metadata 没有明确来源，relatedBookId、relatedBookTitle、relatedChapterIndex 必须输出 null 或省略。
            - 不要从当前打开页面、当前请求 bookId/chapterIndex 推断来源。

            画像边界必须严格遵守：
            - user_style_profile/stylePatch 记录用户喜欢怎样被解释：完整案例、背景解释、直接进入故事、最后回扣原文观点、不喜欢教科书式/空泛/模板化回答。
            - 只有记忆明确表达回答方式偏好时才生成 stylePatch；如果记忆只体现知识掌握或知识薄弱，stylePatch 必须为 null。
            - reading_understanding_profile/readingPatches 只记录用户对某类书籍的知识理解状态：理解程度、掌握了哪些概念/能力、哪些知识点薄弱、是否能理解抽象概念、是否能把概念和案例对应、是否能总结作者观点、是否具备批判性理解或迁移应用能力。
            - user_knowledge_state/knowledgePatches 记录用户对具体知识点的掌握程度，用于回答“接下来读什么”和“可以和以前什么知识关联”。reading_understanding_profile 只保留类别级概览，不承担具体知识点掌握判断。
            - 如果记忆主要围绕单个具体知识点，只生成 knowledgePatch；除非能概括出稳定的类别级阅读状态，否则 readingPatches 输出 []。
            - readingPatches 禁止输出“偏好完整案例和背景解释/直接进入故事/最后回扣原文观点/需要通俗解释”等回答方式偏好。
            - readingPatches 的 strengths、weaknesses、summary 必须描述知识理解状态，而不是回答风格偏好。
            - knowledgePatches 必须围绕具体 topic，例如概念、人物、事件、理论、方法、案例；不要用“社会学整体”“阅读偏好”这类粗粒度或风格主题。
            - 如果记忆体现用户对某个概念理解不清、能否区分概念、是否能总结观点、能否把概念和案例对应，应生成 knowledgePatch。
            - 如果用户明确说“我理解了”，并且能用自己的话解释概念、能和其他概念区分、能举出例子，knowledgePatch.level 必须倾向 basic_understood，而不是 learning。
            - level=learning 用于用户仍在提问、不懂、混淆或需要解释；level=basic_understood 用于用户能解释核心含义并给出正确例子。
            - 如果情景记忆只体现用户偏好解释方式，没有体现知识掌握程度，只更新 stylePatch，不生成 readingPatch 或 knowledgePatch。
            - 与回答方式有关的 newEvidence 必须使用 evidenceDomain="style"。
            - knowledgePatches.domain 只能使用固定 bookCategory 枚举；knowledgeType 只能是 concept/person/event/theory/method/case/other；level 只能是 unknown/exposed/learning/basic_understood/well_understood。

            输出 JSON schema 必须是：
            {
              "stylePatch": {
                "explanationStyle": "通俗、具体、案例化、补充背景",
                "preferredDepth": "medium_to_detailed",
                "prefersExamples": true,
                "prefersStorytelling": true,
                "prefersStepByStep": true,
                "avoidance": ["教科书式回答", "空泛总结", "模板化开头"],
                "summary": "用户偏好完整案例、背景解释、直接进入故事，并希望最后回扣原文观点。",
                "confidenceDelta": 0.1
              },
              "readingPatches": [
                {
                  "bookCategory": "%s",
                  "understandingLevel": "learning",
                  "learningStage": "case_mapping",
                  "strengths": ["能识别某个社会学概念与原文观点之间的关系"],
                  "weaknesses": ["对抽象概念的边界仍不稳定", "需要练习把案例对应到概念"],
                  "preferredExplanation": [],
                  "backgroundNeeds": [],
                  "typicalQuestions": ["这个概念和案例如何对应", "作者观点可以怎样概括"],
                  "summary": "用户对社会学类书籍处于概念理解到案例映射阶段，正在学习把抽象概念、案例和作者观点对应起来。",
                  "confidenceDelta": 0.1
                }
              ],
              "knowledgePatches": [
                {
                  "domain": "%s",
                  "topic": "差序格局",
                  "knowledgeType": "concept",
                  "level": "learning",
                  "confidenceDelta": 0.1,
                  "masteredEvidence": "",
                  "weaknessEvidence": "用户仍需要通过具体案例理解差序格局的边界。",
                  "summary": "用户正在理解差序格局，需要案例辅助建立概念。",
                  "relatedBookId": null,
                  "relatedBookTitle": null,
                  "relatedChapterIndex": null
                }
              ],
              "newEvidence": [
                {
                  "evidenceDomain": "style",
                  "evidenceType": "explanation_preference",
                  "bookCategory": null,
                  "content": "用户要求完整案例、背景解释，并希望最后回扣原文观点。",
                  "importance": 0.82,
                  "relatedBookId": null,
                  "relatedBookTitle": null,
                  "relatedChapterIndex": null
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

            旧知识点掌握状态：
            %s

            当前问题：
            %s

            最近重要情景记忆：
            %s
            """.formatted(
            resolvedCategory,
            resolvedCategory,
            resolvedCategory,
            oldStyleSnapshot,
            oldReadingSnapshot,
            oldKnowledgeSnapshot,
            question == null ? "" : question,
            (memories == null ? List.<MemoryItem>of() : memories).stream()
                .map(this::memoryPromptLine)
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
            - 顶层只允许 stylePatch、readingPatches、knowledgePatches、newEvidence、summary。
            - 禁止 JSON Patch 格式，不能出现 op/path/value。
            - readingPatches 只能是画像字段对象数组，禁止 bookId/chapterIndex/question/answer/readingProgress/currentChapterIndex。
            - knowledgePatches 必须是对象数组；domain 只能用固定 bookCategory；knowledgeType 只能是 concept/person/event/theory/method/case/other；level 只能是 unknown/exposed/learning/basic_understood/well_understood。
            - newEvidence 必须是对象数组，不能是字符串数组。
            - bookCategory/domain 只能是：社会学、技术、历史、文学、哲学、心理学、英语、职业成长、经济学、其他。
            - 回答方式偏好必须进入 stylePatch/newEvidence(evidenceDomain=style)，不要写入 readingPatches。
            - readingPatches 只描述知识理解状态，不描述“喜欢完整案例/背景解释/直接进入故事/回扣原文观点”。
            - 如果只有解释偏好证据，readingPatches 和 knowledgePatches 都输出 []。
            - relatedBookId/relatedBookTitle/relatedChapterIndex 只能来自记忆 metadata 的明确来源；没有明确来源时输出 null 或省略。

            目标 schema：
            {
              "stylePatch": {
                "explanationStyle": "通俗、具体、案例化、补充背景",
                "preferredDepth": "medium_to_detailed",
                "prefersExamples": true,
                "prefersStorytelling": true,
                "prefersStepByStep": true,
                "avoidance": ["教科书式回答", "空泛总结", "模板化开头"],
                "summary": "用户偏好完整案例、背景解释、直接进入故事，并希望最后回扣原文观点。",
                "confidenceDelta": 0.1
              },
              "readingPatches": [],
              "knowledgePatches": [],
              "newEvidence": [
                {
                  "evidenceDomain": "style",
                  "evidenceType": "explanation_preference",
                  "bookCategory": null,
                  "content": "用户要求完整案例和背景解释。",
                  "importance": 0.82,
                  "relatedBookId": null,
                  "relatedBookTitle": null,
                  "relatedChapterIndex": null
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
        List<String> allowedTopLevel = List.of("stylePatch", "readingPatches", "knowledgePatches", "newEvidence", "summary");
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
        JsonNode knowledgePatches = root.get("knowledgePatches");
        if (knowledgePatches != null && !knowledgePatches.isNull()) {
            if (!(knowledgePatches instanceof ArrayNode arrayNode)) {
                throw new IllegalArgumentException("knowledgePatches 必须是对象数组");
            }
            for (JsonNode item : arrayNode) {
                if (!(item instanceof ObjectNode knowledgeObject)) {
                    throw new IllegalArgumentException("knowledgePatches 必须是对象数组");
                }
                validateKnowledgePatch(knowledgeObject);
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

    private void validateKnowledgePatch(ObjectNode knowledgePatch) {
        JsonNode domain = knowledgePatch.get("domain");
        if (domain == null || !domain.isTextual() || !bookCategoryService.isAllowed(domain.asText())) {
            throw new IllegalArgumentException("knowledgePatches.domain 必须属于固定枚举");
        }
        JsonNode topic = knowledgePatch.get("topic");
        if (topic == null || !topic.isTextual() || topic.asText().isBlank()) {
            throw new IllegalArgumentException("knowledgePatches.topic 不能为空");
        }
        JsonNode knowledgeType = knowledgePatch.get("knowledgeType");
        if (knowledgeType == null || !knowledgeType.isTextual()
            || !knowledgeStateService.isAllowedKnowledgeType(knowledgeType.asText())) {
            throw new IllegalArgumentException("knowledgePatches.knowledgeType 不合法");
        }
        JsonNode level = knowledgePatch.get("level");
        if (level == null || !level.isTextual() || !knowledgeStateService.isAllowedLevel(level.asText())) {
            throw new IllegalArgumentException("knowledgePatches.level 不合法");
        }
    }

    private boolean isStylePreferenceReadingPatch(ReadingProfilePatch reading) {
        String text = String.join(" ",
            safeJoin(reading.strengths()),
            safeJoin(reading.weaknesses()),
            safeJoin(reading.preferredExplanation()),
            safeJoin(reading.backgroundNeeds()),
            safeJoin(reading.typicalQuestions()),
            reading.summary() == null ? "" : reading.summary());
        return isStylePreferenceText(text) && !hasUnderstandingSignal(text);
    }

    private boolean isStylePreferenceText(String text) {
        String value = text == null ? "" : text;
        return value.contains("完整案例")
            || value.contains("具体案例")
            || value.contains("背景解释")
            || value.contains("直接进入故事")
            || value.contains("最后回扣")
            || value.contains("回扣原文")
            || value.contains("通俗")
            || value.contains("教科书式")
            || value.contains("模板化")
            || value.contains("空泛");
    }

    private boolean hasUnderstandingSignal(String text) {
        String value = text == null ? "" : text;
        return value.contains("理解程度")
            || value.contains("掌握")
            || value.contains("概念边界")
            || value.contains("概念和案例")
            || value.contains("知识点")
            || value.contains("作者观点")
            || value.contains("批判")
            || value.contains("迁移")
            || value.contains("应用")
            || value.contains("区分")
            || value.contains("总结")
            || value.contains("对应起来");
    }

    private String memoryPromptLine(MemoryItem memory) {
        return "- id=" + memory.getId()
            + ", importance=" + memory.getImportance()
            + ", content=" + memory.getContent()
            + ", metadata=" + profileJson.writeObject(memory.getMetadata());
    }

    private String safeJoin(List<String> values) {
        return values == null ? "" : String.join(" ", values);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ExtractedProfilePatch(ProfileUpdatePatch patch, String rawJson) {
    }
}
