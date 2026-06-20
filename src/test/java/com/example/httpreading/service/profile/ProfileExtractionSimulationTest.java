package com.example.httpreading.service.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.example.httpreading.domain.profile.UserKnowledgeState;
import com.example.httpreading.memory.model.MemoryItem;
import com.example.httpreading.repository.BooksRepository;
import com.example.httpreading.service.ModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProfileExtractionSimulationTest {
    private ModelClient modelClient;
    private UserKnowledgeStateService knowledgeStateService;
    private ProfilePatchExtractor extractor;
    private KnowledgeStateInsightService insightService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        ProfileJson profileJson = new ProfileJson(objectMapper);
        modelClient = mock(ModelClient.class);
        BookCategoryService bookCategoryService = new BookCategoryService(mock(BooksRepository.class), modelClient);
        knowledgeStateService = mock(UserKnowledgeStateService.class);
        when(knowledgeStateService.isAllowedKnowledgeType(anyString())).thenAnswer(invocation ->
            List.of("concept", "person", "event", "theory", "method", "case", "other").contains(invocation.getArgument(0)));
        when(knowledgeStateService.isAllowedLevel(anyString())).thenAnswer(invocation ->
            List.of("unknown", "exposed", "learning", "basic_understood", "well_understood").contains(invocation.getArgument(0)));
        extractor = new ProfilePatchExtractor(modelClient, objectMapper, profileJson, bookCategoryService, knowledgeStateService);
        insightService = new KnowledgeStateInsightService(knowledgeStateService, bookCategoryService);
    }

    @Test
    void scenarioAStylePreferenceOnly() {
        when(modelClient.chat(anyString())).thenReturn(styleOnlyPatch());

        var result = extractor.extract(
            memories(
                "这个回答太教科书了，我希望通俗一点。",
                "不要一上来简单来说，直接讲故事。",
                "举例子要完整，要讲清楚前因后果。"),
            null,
            List.of(),
            List.of(),
            "社会学",
            "模拟样例 A");

        assertNotNull(result);
        assertNotNull(result.patch().stylePatch());
        assertEquals(0, result.patch().readingPatches().size());
        assertEquals(0, result.patch().knowledgePatches().size());
    }

    @Test
    void scenarioBSpecificKnowledgeWeakness() {
        when(modelClient.chat(anyString())).thenReturn(knowledgeWeaknessPatch());

        var result = extractor.extract(
            memories(
                "差序格局到底是什么意思？",
                "差序格局和普通人际关系有什么区别？",
                "我还是不太懂差序格局为什么能说明乡土社会。"),
            null,
            List.of(),
            List.of(),
            "社会学",
            "模拟样例 B");

        assertNotNull(result);
        var patch = result.patch().knowledgePatches().get(0);
        assertEquals("社会学", patch.domain());
        assertEquals("差序格局", patch.topic());
        assertEquals("learning", patch.level());
        assertTrue(patch.weaknessEvidence().contains("含义"));
        assertTrue(patch.weaknessEvidence().contains("乡土社会"));
    }

    @Test
    void scenarioCKnowledgeProgress() {
        when(modelClient.chat(anyString())).thenReturn(knowledgeProgressPatch());

        var result = extractor.extract(
            memories(
                "我理解了，差序格局就是以自己为中心向外推开的关系网络。",
                "它和现代社会的契约关系不同，乡土社会更依赖熟人关系和亲疏远近。",
                "比如亲戚、熟人、陌生人的责任边界不同。"),
            null,
            List.of(),
            List.of(),
            "社会学",
            "模拟样例 C");

        assertNotNull(result);
        var patch = result.patch().knowledgePatches().get(0);
        assertEquals("basic_understood", patch.level());
        assertTrue(patch.masteredEvidence().contains("以自己为中心向外推开的关系网络"));
        assertTrue(patch.masteredEvidence().contains("熟人社会"));
        assertTrue(patch.masteredEvidence().contains("关系边界例子"));
    }

    @Test
    void scenarioDRecommendNextReadingFromKnowledgeState() {
        when(knowledgeStateService.listByUserAndDomain("u1", "社会学")).thenReturn(List.of(
            state("乡土社会", "basic_understood", "用户已基本理解乡土社会。", ""),
            state("差序格局", "learning", "用户正在理解差序格局。", "用户仍需要补强差序格局。"),
            state("礼治秩序", "unknown", "", "用户尚未理解礼治秩序。")));

        var recommendations = insightService.recommendNextReadings("u1", "社会学");

        assertEquals("差序格局", recommendations.get(0).topic());
        assertEquals("礼治秩序", recommendations.get(1).topic());
        assertTrue(recommendations.get(0).reason().contains("学习中"));
        assertTrue(recommendations.get(1).reason().contains("尚未建立理解"));
    }

    @Test
    void scenarioERelateToOldKnowledge() {
        when(knowledgeStateService.listByUserAndDomain("u1", "社会学")).thenReturn(List.of(
            state("乡土社会", "basic_understood", "用户理解乡土社会依赖地方共同体和熟人关系。", ""),
            state("差序格局", "learning", "用户正在理解差序格局。", "用户仍需理解亲疏远近的关系结构。"),
            state("熟人社会", "basic_understood", "用户理解熟人社会中的关系、责任和信任边界。", ""),
            state("礼治秩序", "unknown", "", "")));

        var relations = insightService.relateToKnownKnowledge("u1", "社会学", "礼治秩序");

        assertEquals(List.of("乡土社会", "熟人社会", "差序格局"),
            relations.stream().map(KnowledgeStateInsightService.KnowledgeRelation::topic).toList());
        assertTrue(relations.get(0).reason().contains("礼治秩序可以和乡土社会关联"));
        assertTrue(relations.get(1).reason().contains("熟人社会"));
        assertTrue(relations.get(2).reason().contains("差序格局"));
    }

    private List<MemoryItem> memories(String... contents) {
        return java.util.stream.IntStream.range(0, contents.length)
            .mapToObj(index -> new MemoryItem(
                "m" + index,
                contents[index],
                "episodic",
                "u1",
                LocalDateTime.now().minusMinutes(index),
                0.8f,
                Map.of()))
            .toList();
    }

    private UserKnowledgeState state(String topic, String level, String mastered, String weakness) {
        UserKnowledgeState state = new UserKnowledgeState();
        state.setUserId("u1");
        state.setDomain("社会学");
        state.setTopic(topic);
        state.setKnowledgeType("concept");
        state.setLevel(level);
        state.setMasteredEvidence(mastered);
        state.setWeaknessEvidence(weakness);
        state.setSummary(mastered == null || mastered.isBlank() ? weakness : mastered);
        return state;
    }

    private String styleOnlyPatch() {
        return """
            {
              "stylePatch": {
                "explanationStyle": "通俗、故事化、完整案例",
                "preferredDepth": "medium_to_detailed",
                "prefersExamples": true,
                "prefersStorytelling": true,
                "prefersStepByStep": true,
                "avoidance": ["教科书式回答", "简单来说式开头"],
                "summary": "用户希望回答通俗，直接讲故事，并用完整案例讲清前因后果。",
                "confidenceDelta": 0.1
              },
              "readingPatches": [],
              "knowledgePatches": [],
              "newEvidence": [
                {
                  "evidenceDomain": "style",
                  "evidenceType": "explanation_preference",
                  "bookCategory": null,
                  "content": "用户不喜欢教科书式回答，希望直接讲故事并使用完整案例。",
                  "importance": 0.82
                }
              ],
              "summary": "更新用户解释风格"
            }
            """;
    }

    private String knowledgeWeaknessPatch() {
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
                  "summary": "用户正在理解差序格局，但仍不清楚其含义、边界以及为什么能说明乡土社会。"
                }
              ],
              "newEvidence": [],
              "summary": "更新差序格局知识点状态"
            }
            """;
    }

    private String knowledgeProgressPatch() {
        return """
            {
              "stylePatch": null,
              "readingPatches": [],
              "knowledgePatches": [
                {
                  "domain": "社会学",
                  "topic": "差序格局",
                  "knowledgeType": "concept",
                  "level": "basic_understood",
                  "confidenceDelta": 0.15,
                  "masteredEvidence": "用户能解释差序格局是以自己为中心向外推开的关系网络，并能举出熟人社会中的关系边界例子。",
                  "weaknessEvidence": "",
                  "summary": "用户已基本理解差序格局，并能和熟人社会关系边界建立对应。"
                }
              ],
              "newEvidence": [],
              "summary": "更新差序格局掌握进步"
            }
            """;
    }
}
