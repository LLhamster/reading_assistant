package com.example.httpreading.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import com.example.httpreading.mcp.ExternalMcpClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanValidatorTest {
    private PlanValidator validator;
    private ExternalMcpClientService externalMcpClientService;

    @BeforeEach
    void setUp() {
        externalMcpClientService = mock(ExternalMcpClientService.class);
        when(externalMcpClientService.routableServers()).thenReturn(List.of());
        validator = new PlanValidator(new ToolRegistry(), externalMcpClientService);
    }

    @Test
    void acceptsValidSingleToolPlan() {
        ChatPlan plan = validator.validateAndConvert(response(
            "READING_QA",
            "SINGLE_TOOL",
            List.of("rag.search"),
            List.of(step("rag.search")),
            1), "问题");

        assertEquals(ToolExecutionMode.SINGLE_TOOL, plan.executionMode());
        assertEquals("rag.search", plan.toolPlan().get(0).toolName());
    }

    @Test
    void rejectsInvalidTaskType() {
        assertThrows(PlanValidationException.class, () -> validator.validateAndConvert(response(
            "GITHUB_ANALYSIS",
            "NO_TOOL",
            List.of(),
            List.of(),
            0), "问题"));
    }

    @Test
    void rejectsUnknownTool() {
        assertThrows(PlanValidationException.class, () -> validator.validateAndConvert(response(
            "READING_QA",
            "SINGLE_TOOL",
            List.of("system.delete_all"),
            List.of(new LlmToolStep("system.delete_all", Map.of(), "危险工具")),
            1), "问题"));
    }

    @Test
    void rejectsToolNotListedInAllowedTools() {
        assertThrows(PlanValidationException.class, () -> validator.validateAndConvert(response(
            "READING_QA",
            "SINGLE_TOOL",
            List.of("memory.search"),
            List.of(step("rag.search")),
            1), "问题"));
    }

    @Test
    void rejectsNoToolWithSteps() {
        assertThrows(PlanValidationException.class, () -> validator.validateAndConvert(response(
            "READING_QA",
            "NO_TOOL",
            List.of("rag.search"),
            List.of(step("rag.search")),
            1), "问题"));
    }

    @Test
    void rejectsMaxStepsOverFive() {
        assertThrows(PlanValidationException.class, () -> validator.validateAndConvert(response(
            "READING_QA",
            "SINGLE_TOOL",
            List.of("rag.search"),
            List.of(step("rag.search")),
            6), "问题"));
    }

    @Test
    void rejectsDisabledWriteTool() {
        assertThrows(PlanValidationException.class, () -> validator.validateAndConvert(response(
            "TOOL_ACTION",
            "SINGLE_TOOL",
            List.of("learning_plan.save"),
            List.of(new LlmToolStep("learning_plan.save", Map.of("planContent", "计划"), "保存计划")),
            1), "问题"));
    }

    @Test
    void rejectsBoundedReactWithoutMcpServer() {
        assertThrows(PlanValidationException.class, () -> validator.validateAndConvert(response(
            "READING_QA",
            "BOUNDED_REACT",
            List.of(),
            List.of(),
            0), "问题"));
    }

    @Test
    void acceptsBoundedReactWithEnabledMcpServerToken() {
        when(externalMcpClientService.routableServers()).thenReturn(List.of(Map.of(
            "name", "github",
            "description", "GitHub 仓库资料",
            "allowedTools", List.of("search_repositories"))));

        ChatPlan plan = validator.validateAndConvert(response(
            "TOOL_ACTION",
            "BOUNDED_REACT",
            List.of("mcp.server:github"),
            List.of(),
            5,
            "EXTERNAL_SEARCH_REQUIRED",
            true), "使用github搜索httpreading的项目");

        assertEquals(ToolExecutionMode.BOUNDED_REACT, plan.executionMode());
        assertEquals(List.of("mcp.server:github"), plan.allowedTools());
    }

    @Test
    void rejectsGithubQuestionUsingRagOrMemoryAsSubstitute() {
        assertThrows(PlanValidationException.class, () -> validator.validateAndConvert(response(
            "READING_QA",
            "MULTI_TOOL",
            List.of("memory.search", "rag.search"),
            List.of(step("memory.search"), step("rag.search")),
            2), "使用github搜索httpreading的项目"));
    }

    @Test
    void rejectsRealtimeExternalQuestionWithTextOnlyMode() {
        LlmPlanResponse response = response(
            "GENERAL_QA",
            "NO_TOOL",
            List.of(),
            List.of(),
            0,
            "TEXT_ONLY",
            true);

        assertThrows(PlanValidationException.class,
            () -> validator.validateAndConvert(response, "github 上 httpreading 最新项目是什么"));
    }

    @Test
    void acceptsUnsupportedExternalNoToolPlan() {
        LlmPlanResponse response = response(
            "GENERAL_QA",
            "NO_TOOL",
            List.of(),
            List.of(),
            0,
            "EXTERNAL_SEARCH_REQUIRED",
            true);

        ChatPlan plan = validator.validateAndConvert(response, "使用github搜索httpreading的项目");

        assertEquals(ToolExecutionMode.NO_TOOL, plan.executionMode());
        assertEquals(AnswerMode.EXTERNAL_SEARCH_REQUIRED, plan.answerMode());
    }

    @Test
    void rejectsContextToolWhenPlanDoesNotDependOnContext() {
        assertThrows(PlanValidationException.class, () -> validator.validateAndConvert(response(
            "READING_QA",
            "SINGLE_TOOL",
            List.of("context.get_current_page"),
            List.of(step("context.get_current_page")),
            1,
            "TEXT_ONLY",
            false), "解释差序格局"));
    }

    private LlmPlanResponse response(String taskType,
                                     String executionMode,
                                     List<String> allowedTools,
                                     List<LlmToolStep> toolPlan,
                                     int maxSteps) {
        return new LlmPlanResponse(
            taskType,
            "NONE",
            "独立问题",
            true,
            executionMode,
            allowedTools,
            toolPlan,
            "任务目标",
            maxSteps,
            "停止条件",
            "回答指导",
            "TEXT_ONLY",
            "STRICT",
            new LlmAnswerRequirement(false, false, false, false, false, false,
                false, false, false, "MEDIUM"),
            "规划原因");
    }

    private LlmPlanResponse response(String taskType,
                                     String executionMode,
                                     List<String> allowedTools,
                                     List<LlmToolStep> toolPlan,
                                     int maxSteps,
                                     String answerMode,
                                     boolean dependsOnContext) {
        return new LlmPlanResponse(
            taskType,
            "NONE",
            "独立问题",
            dependsOnContext,
            executionMode,
            allowedTools,
            toolPlan,
            "任务目标",
            maxSteps,
            "停止条件",
            "回答指导",
            answerMode,
            "STRICT",
            new LlmAnswerRequirement(false, false, false, false, false, false,
                false, false, false, "MEDIUM"),
            "规划原因");
    }

    private LlmToolStep step(String toolName) {
        return new LlmToolStep(toolName, Map.of("query", "q"), "测试");
    }
}
