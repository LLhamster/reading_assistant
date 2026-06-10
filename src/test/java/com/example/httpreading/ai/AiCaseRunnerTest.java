package com.example.httpreading.ai;

import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.mcp.ExternalMcpClientService;
import com.example.httpreading.service.ModelClient;
import com.example.httpreading.service.ai.ChatPlan;
import com.example.httpreading.service.ai.CollectedEvidence;
import com.example.httpreading.service.ai.FinalAnswerService;
import com.example.httpreading.service.ai.PlanValidator;
import com.example.httpreading.service.ai.PlannerPromptBuilder;
import com.example.httpreading.service.ai.PlannerService;
import com.example.httpreading.service.ai.ToolRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AiCaseRunnerTest {
    private static final List<String> CASE_FILES = List.of(
        "ai-cases/planner_cases.json",
        "ai-cases/final_answer_cases.json",
        "ai-cases/evidence_boundary_cases.json");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aiCasesPass() throws IOException {
        List<AiCaseSpec> cases = loadCases();
        List<AiCaseAssertions.AiCaseFailure> failures = new ArrayList<>();
        int passed = 0;

        for (AiCaseSpec spec : cases) {
            List<AiCaseAssertions.AiCaseFailure> caseFailures = runCase(spec);
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

    private List<AiCaseAssertions.AiCaseFailure> runCase(AiCaseSpec spec) {
        List<AiCaseAssertions.AiCaseFailure> failures = new ArrayList<>();
        AiChatRequest request = request(spec.input);
        ExternalMcpClientService externalMcpClientService = externalMcpClientService(spec.mcpServers);
        PlannerService plannerService = plannerService(spec, externalMcpClientService);
        ChatPlan plan = plannerService.plan(request);

        failures.addAll(AiCaseAssertions.assertPlanMatches(spec, plan));

        if (spec.expectedAnswerRules != null) {
            CollectedEvidence evidence = TestEvidenceFactory.from(spec.mockEvidence);
            FinalAnswerService finalAnswerService = finalAnswerService(spec);
            String answer = finalAnswerService.answer(request, plan, evidence);
            failures.addAll(AiCaseAssertions.assertAnswerMatches(spec, answer, evidence));
        }
        return failures;
    }

    private PlannerService plannerService(AiCaseSpec spec, ExternalMcpClientService externalMcpClientService) {
        ToolRegistry toolRegistry = new ToolRegistry();
        return new PlannerService(
            modelClient(spec.mockPlannerResponse),
            objectMapper,
            new PlannerPromptBuilder(toolRegistry, externalMcpClientService),
            new PlanValidator(toolRegistry, externalMcpClientService),
            externalMcpClientService);
    }

    private FinalAnswerService finalAnswerService(AiCaseSpec spec) {
        return new FinalAnswerService(modelClient(spec.mockFinalAnswer));
    }

    private ModelClient modelClient(String response) {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.chat(anyString())).thenReturn(response == null ? "" : response);
        return modelClient;
    }

    private ExternalMcpClientService externalMcpClientService(List<Map<String, Object>> routableServers) {
        ExternalMcpClientService service = mock(ExternalMcpClientService.class);
        when(service.routableServers()).thenReturn(routableServers == null ? List.of() : routableServers);
        return service;
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

    private String report(int total, int passed, List<AiCaseAssertions.AiCaseFailure> failures) {
        StringBuilder report = new StringBuilder()
            .append("\n[AI_CASE_REPORT]\n")
            .append("total=").append(total).append('\n')
            .append("passed=").append(passed).append('\n')
            .append("failed=").append(failures.size()).append('\n');
        for (AiCaseAssertions.AiCaseFailure failure : failures) {
            report.append('\n').append(failure.reportText());
        }
        return report.toString();
    }
}
