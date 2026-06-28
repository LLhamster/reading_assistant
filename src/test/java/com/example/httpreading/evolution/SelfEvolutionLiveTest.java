package com.example.httpreading.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@EnabledIfSystemProperty(named = "selfEvolution.live", matches = "true")
class SelfEvolutionLiveTest {
    @Autowired
    private SelfEvolutionService service;
    @Autowired
    private SelfEvolutionReportWriter reportWriter;

    @DynamicPropertySource
    static void liveModelProperties(DynamicPropertyRegistry registry) {
        String apiKey = firstNonBlank(System.getProperty("model.apiKey"), System.getenv("MODEL_API_KEY"));
        registry.add("model.apiKey", () -> apiKey);
        registry.add("model.baseUrl", () -> firstNonBlank(
            System.getProperty("model.baseUrl"), System.getenv("MODEL_BASE_URL"),
            "https://api.deepseek.com/chat/completions"));
        registry.add("model.chatModel", () -> firstNonBlank(
            System.getProperty("model.chatModel"), System.getenv("MODEL_CHAT_MODEL"), "deepseek-chat"));
    }

    @Test
    void runLocalSelfEvolutionExperiment() throws Exception {
        assumeTrue(!firstNonBlank(System.getProperty("model.apiKey"), System.getenv("MODEL_API_KEY")).isBlank(),
            "Set MODEL_API_KEY or -Dmodel.apiKey");
        String userId = System.getProperty("selfEvolution.userId", "default_user");
        int memoryLimit = Integer.parseInt(System.getProperty("selfEvolution.memoryLimit", "50"));
        int caseCount = Integer.parseInt(System.getProperty("selfEvolution.caseCount", "30"));
        long bookId = Long.parseLong(System.getProperty("selfEvolution.defaultBookId", "1"));
        int chapterIndex = Integer.parseInt(System.getProperty("selfEvolution.defaultChapterIndex", "1"));
        Path reportRoot = Path.of(System.getProperty("selfEvolution.reportDir", "target/evolution"));

        SelfEvolutionReport report = service.run(userId, memoryLimit, bookId, chapterIndex, caseCount);
        Path output = reportWriter.write(report, reportRoot);

        int expectedCount = Math.max(1, Math.min(100, caseCount));
        assertEquals(expectedCount, report.evalCases().size());
        assertEquals(expectedCount, report.baselineResults().size());
        assertTrue(report.candidateResults().isEmpty()
            || report.candidateResults().size() == expectedCount);
        System.out.println("Self-Evolution report: " + output.toAbsolutePath());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }
}
