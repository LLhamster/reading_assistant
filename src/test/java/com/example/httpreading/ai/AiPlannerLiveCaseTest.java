package com.example.httpreading.ai;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.mcp.ExternalMcpClientService;
import com.example.httpreading.service.ModelClient;
import com.example.httpreading.service.ai.ChatPlan;
import com.example.httpreading.service.ai.PlanValidator;
import com.example.httpreading.service.ai.PlannerPromptBuilder;
import com.example.httpreading.service.ai.PlannerService;
import com.example.httpreading.service.ai.ToolRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AiPlannerLiveCaseTest {
    private static final List<String> CASE_FILES = List.of(
        "ai-cases/planner_cases.json",
        "ai-cases/final_answer_cases.json",
        "ai-cases/evidence_boundary_cases.json");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void livePlannerMatchesExpectedPlan() throws Exception {
        assumeTrue(Boolean.getBoolean("ai.live"),
            "Live AI cases are disabled by default. Run with -Dai.live=true to enable.");

        List<AiCaseSpec> cases = loadCases().stream()
            .filter(spec -> spec.expectedPlan != null)
            .toList();
        List<AiCaseAssertions.AiCaseFailure> failures = new ArrayList<>();
        int passed = 0;

        for (AiCaseSpec spec : cases) {
            ExternalMcpClientService externalMcpClientService = externalMcpClientService(spec.mcpServers);
            PlannerService plannerService = plannerService(externalMcpClientService);
            ChatPlan plan = plannerService.plan(request(spec.input));
            List<AiCaseAssertions.AiCaseFailure> caseFailures = AiCaseAssertions.assertPlanMatches(spec, plan);
            if (caseFailures.isEmpty()) {
                passed++;
            } else {
                failures.addAll(caseFailures);
            }
        }

        if (!failures.isEmpty()) {
            fail(report(cases.size(), passed, failures));
        }
        System.out.println(report(cases.size(), passed, failures));
    }

    private PlannerService plannerService(ExternalMcpClientService externalMcpClientService) throws Exception {
        ToolRegistry toolRegistry = new ToolRegistry();
        return new PlannerService(
            liveModelClient(),
            objectMapper,
            new PlannerPromptBuilder(externalMcpClientService),
            new PlanValidator(toolRegistry, externalMcpClientService),
            externalMcpClientService);
    }

    private ModelClient liveModelClient() throws Exception {
        ModelClient modelClient = new ModelClient();
        String apiKey = firstNonBlank(System.getProperty("model.apiKey"), System.getenv("MODEL_API_KEY"));
        if (!apiKey.isBlank()) {
            setField(modelClient, "apiKey", apiKey);
        }
        return modelClient;
    }

    private ExternalMcpClientService externalMcpClientService(List<Map<String, Object>> routableServers) {
        ExternalMcpClientService service = mock(ExternalMcpClientService.class);
        when(service.routableServers()).thenReturn(routableServers == null ? List.of(selfLocalServer()) : routableServers);
        return service;
    }

    private Map<String, Object> selfLocalServer() {
        return Map.of(
            "name", "self-local",
            "description", "本项目内部阅读系统能力",
            "allowedTools", List.of(
                "memory_search",
                "rag_retrieve",
                "context_get_recent_dialogue",
                "context_get_current_page",
                "context_build"));
    }

    private AiChatRequest request(AiCaseSpec.AiCaseInput input) {
        AiChatRequest request = new AiChatRequest();
        request.setUserId(input.userId);
        request.setSessionId(input.sessionId);
        request.setBookId(input.bookId == null ? 1L : input.bookId);
        request.setChapterIndex(input.chapterIndex == null ? 1 : input.chapterIndex);
        request.setChapterTitle(input.chapterTitle);
        request.setQuestion(input.question);
        request.setSelectedText(input.selectedText);
        request.setSelectedContext(input.selectedContext);
        request.setEnableMemory(input.memoryEnabled);
        request.setEnableRag(input.ragEnabled);
        request.setEnableExternalMcp(input.externalMcpEnabled);
        return request;
    }

    private List<AiCaseSpec> loadCases() throws IOException {
        List<AiCaseSpec> cases = new ArrayList<>();
        for (String file : CASE_FILES) {
            try (InputStream inputStream = resource(file)) {
                cases.addAll(objectMapper.readValue(inputStream, new TypeReference<List<AiCaseSpec>>() {
                }));
            }
        }
        return cases;
    }

    private InputStream resource(String file) {
        InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(file);
        if (inputStream == null) {
            throw new IllegalStateException("AI case file not found: " + file);
        }
        return inputStream;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String report(int total, int passed, List<AiCaseAssertions.AiCaseFailure> failures) {
        StringBuilder report = new StringBuilder()
            .append("\n[AI_PLANNER_LIVE_CASE_REPORT]\n")
            .append("total=").append(total).append('\n')
            .append("passed=").append(passed).append('\n')
            .append("failed=").append(failures.size()).append('\n');
        for (AiCaseAssertions.AiCaseFailure failure : failures) {
            report.append('\n').append(failure.reportText());
        }
        return report.toString();
    }
}
