package com.example.httpreading.service.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.example.httpreading.memory.model.MemoryItem;
import com.example.httpreading.repository.BooksRepository;
import com.example.httpreading.repository.UserKnowledgeStateRepository;
import com.example.httpreading.service.ModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = {
    ProfileExtractionLiveSimulationTest.TestConfig.class,
    ModelClient.class,
    ProfileJson.class,
    BookCategoryService.class,
    UserKnowledgeStateService.class,
    ProfilePatchExtractor.class
})
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "profile.extraction.live", matches = "true")
class ProfileExtractionLiveSimulationTest {
    @Autowired
    private ProfilePatchExtractor extractor;

    @Value("${model.apiKey:}")
    private String apiKey;

    @MockBean
    private BooksRepository booksRepository;

    @MockBean
    private UserKnowledgeStateRepository userKnowledgeStateRepository;

    @BeforeEach
    void requireApiKey() {
        assumeTrue(apiKey != null && !apiKey.isBlank(),
            "Live profile extraction cases require model.apiKey in application.yml or -Dmodel.apiKey=...");
    }

    @Test
    void liveScenarioAStylePreferenceOnly() {
        var result = extractor.extract(
            memories(
                "这个回答太教科书了，我希望通俗一点。",
                "不要一上来简单来说，直接讲故事。",
                "举例子要完整，要讲清楚前因后果。"),
            null,
            List.of(),
            List.of(),
            "社会学",
            "请根据这些模拟记忆更新画像");

        System.out.println("[PROFILE_LIVE_A] " + result.rawJson());
        assertNotNull(result);
        assertNotNull(result.patch().stylePatch());
        assertEquals(0, result.patch().readingPatches().size(), "纯风格偏好不应生成 readingPatches");
        assertEquals(0, result.patch().knowledgePatches().size(), "纯风格偏好不应生成 knowledgePatches");
    }

    @Test
    void liveScenarioBSpecificKnowledgeWeakness() {
        var result = extractor.extract(
            memories(
                "差序格局到底是什么意思？",
                "差序格局和普通人际关系有什么区别？",
                "我还是不太懂差序格局为什么能说明乡土社会。"),
            null,
            List.of(),
            List.of(),
            "社会学",
            "请根据这些模拟记忆更新画像");

        System.out.println("[PROFILE_LIVE_B] " + result.rawJson());
        assertNotNull(result);
        assertTrue(result.patch().knowledgePatches().stream()
            .anyMatch(patch -> "社会学".equals(patch.domain())
                && "差序格局".equals(patch.topic())
                && "learning".equals(patch.level())),
            "应生成差序格局 learning 知识点；具体 weaknessEvidence 以打印的 JSON 人工检查");
    }

    @Test
    void liveScenarioCKnowledgeProgress() {
        var result = extractor.extract(
            memories(
                "我理解了，差序格局就是以自己为中心向外推开的关系网络。",
                "它和现代社会的契约关系不同，乡土社会更依赖熟人关系和亲疏远近。",
                "比如亲戚、熟人、陌生人的责任边界不同。"),
            null,
            List.of(),
            List.of(),
            "社会学",
            "请根据这些模拟记忆更新画像");

        System.out.println("[PROFILE_LIVE_C] " + result.rawJson());
        assertNotNull(result);
        assertTrue(result.patch().knowledgePatches().stream()
            .anyMatch(patch -> "社会学".equals(patch.domain())
                && "差序格局".equals(patch.topic())
                && "basic_understood".equals(patch.level())),
            "应生成差序格局 basic_understood 知识点；具体 masteredEvidence 以打印的 JSON 人工检查");
    }

    private List<MemoryItem> memories(String... contents) {
        return java.util.stream.IntStream.range(0, contents.length)
            .mapToObj(index -> new MemoryItem(
                "live-m" + index,
                contents[index],
                "episodic",
                "u1",
                LocalDateTime.now().minusMinutes(index),
                0.8f,
                Map.of()))
            .toList();
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
