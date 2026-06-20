package com.example.httpreading.service.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.example.httpreading.memory.model.MemoryItem;
import com.example.httpreading.repository.BooksRepository;
import com.example.httpreading.service.ModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProfilePatchExtractorTest {
    private ModelClient modelClient;
    private ProfilePatchExtractor extractor;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        ProfileJson profileJson = new ProfileJson(objectMapper);
        modelClient = mock(ModelClient.class);
        BookCategoryService bookCategoryService = new BookCategoryService(mock(BooksRepository.class), modelClient);
        UserKnowledgeStateService knowledgeStateService = mock(UserKnowledgeStateService.class);
        when(knowledgeStateService.isAllowedKnowledgeType(anyString())).thenAnswer(invocation ->
            List.of("concept", "person", "event", "theory", "method", "case", "other").contains(invocation.getArgument(0)));
        when(knowledgeStateService.isAllowedLevel(anyString())).thenAnswer(invocation ->
            List.of("unknown", "exposed", "learning", "basic_understood", "well_understood").contains(invocation.getArgument(0)));
        extractor = new ProfilePatchExtractor(modelClient, objectMapper, profileJson, bookCategoryService, knowledgeStateService);
    }

    @Test
    void markdownCodeFenceJsonCanBeExtracted() {
        when(modelClient.chat(anyString())).thenReturn("```json\n" + validKnowledgePatch() + "\n```");

        var result = extract();

        assertNotNull(result);
        assertEquals("差序格局", result.patch().knowledgePatches().get(0).topic());
    }

    @Test
    void jsonPatchReadingPatchIsRejected() {
        when(modelClient.chat(anyString())).thenReturn(jsonPatchStyle(), jsonPatchStyle());

        var result = extract();

        assertEquals(null, result);
    }

    @Test
    void stringEvidenceArrayFailsThenRetryCanSucceed() {
        when(modelClient.chat(anyString())).thenReturn(invalidStringEvidence(), validKnowledgePatch());

        var result = extract();

        assertNotNull(result);
        assertEquals("差序格局", result.patch().knowledgePatches().get(0).topic());
        verify(modelClient, org.mockito.Mockito.times(2)).chat(anyString());
    }

    @Test
    void illegalKnowledgePatchIsRejected() {
        when(modelClient.chat(anyString())).thenReturn(invalidKnowledgePatch(), invalidKnowledgePatch());

        var result = extract();

        assertEquals(null, result);
    }

    @Test
    void stylePreferenceReadingPatchIsFiltered() {
        when(modelClient.chat(anyString())).thenReturn(styleOnlyPatchWithMisplacedReadingPatch());

        var result = extract();

        assertNotNull(result);
        assertNotNull(result.patch().stylePatch());
        assertEquals(0, result.patch().readingPatches().size());
        assertEquals(0, result.patch().knowledgePatches().size());
    }

    @Test
    void promptIncludesMemoryMetadataForSourceExtraction() {
        when(modelClient.chat(anyString())).thenReturn(validKnowledgePatch());

        extract();

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(modelClient).chat(prompt.capture());
        org.junit.jupiter.api.Assertions.assertTrue(prompt.getValue().contains("\"bookId\":44"));
        org.junit.jupiter.api.Assertions.assertTrue(prompt.getValue().contains("\"chapterIndex\":2"));
    }

    private ProfilePatchExtractor.ExtractedProfilePatch extract() {
        return extractor.extract(
            memories(),
            null,
            List.of(),
            List.of(),
            "社会学",
            "更新画像");
    }

    private List<MemoryItem> memories() {
        return List.of(new MemoryItem(
            "m1",
            "差序格局到底是什么意思？",
            "episodic",
            "u1",
            LocalDateTime.now(),
            0.8f,
            Map.of("bookId", 44, "bookTitle", "乡土中国", "chapterIndex", 2)));
    }

    private String validKnowledgePatch() {
        return """
            {
              "stylePatch": null,
              "readingPatches": [],
              "knowledgePatches": [
                {
                  "domain": "社会学",
                  "topic": "差序格局",
                  "knowledgeType": "concept",
                  "level": "learning",
                  "confidenceDelta": 0.1,
                  "masteredEvidence": "",
                  "weaknessEvidence": "用户仍难以理解差序格局的含义及其与乡土社会的关系。",
                  "summary": "用户正在理解差序格局。",
                  "relatedBookId": 44,
                  "relatedBookTitle": "乡土中国",
                  "relatedChapterIndex": 2
                }
              ],
              "newEvidence": [],
              "summary": "更新知识点状态"
            }
            """;
    }

    private String invalidStringEvidence() {
        return """
            {
              "stylePatch": null,
              "readingPatches": [],
              "knowledgePatches": [],
              "newEvidence": ["用户喜欢完整案例"],
              "summary": "bad"
            }
            """;
    }

    private String jsonPatchStyle() {
        return """
            {
              "stylePatch": null,
              "readingPatches": [
                {
                  "op": "replace",
                  "path": "/readingProgress",
                  "value": "80%",
                  "bookId": 44,
                  "chapterIndex": 1,
                  "question": "q",
                  "answer": "a"
                }
              ],
              "knowledgePatches": [],
              "newEvidence": [],
              "summary": "bad"
            }
            """;
    }

    private String invalidKnowledgePatch() {
        return """
            {
              "stylePatch": null,
              "readingPatches": [],
              "knowledgePatches": [
                {
                  "domain": "随便生成",
                  "topic": "差序格局",
                  "knowledgeType": "made_up",
                  "level": "expert",
                  "summary": "bad"
                }
              ],
              "newEvidence": [],
              "summary": "bad"
            }
            """;
    }

    private String styleOnlyPatchWithMisplacedReadingPatch() {
        return """
            {
              "stylePatch": {
                "explanationStyle": "通俗、具体、案例化、补充背景",
                "preferredDepth": "medium_to_detailed",
                "prefersExamples": true,
                "prefersStorytelling": true,
                "prefersStepByStep": true,
                "avoidance": ["教科书式回答", "空泛总结"],
                "summary": "用户偏好完整案例、背景解释、直接进入故事。",
                "confidenceDelta": 0.1
              },
              "readingPatches": [
                {
                  "bookCategory": "社会学",
                  "understandingLevel": "learning",
                  "learningStage": "case_mapping",
                  "strengths": ["能识别回答是否空泛"],
                  "weaknesses": ["抽象概念需要具体案例支撑"],
                  "preferredExplanation": ["直接进入故事", "最后回扣原文观点"],
                  "backgroundNeeds": ["历史背景"],
                  "typicalQuestions": ["能否举一个实际例子"],
                  "summary": "用户阅读社会学类书籍时偏好完整案例和背景解释。",
                  "confidenceDelta": 0.1
                }
              ],
              "knowledgePatches": [],
              "newEvidence": [],
              "summary": "更新用户解释风格"
            }
            """;
    }
}
