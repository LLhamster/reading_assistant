package com.example.httpreading.service.ai;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.httpreading.mcp.ExternalMcpClientService;
import org.springframework.stereotype.Service;

@Service
public class PlanValidator {
    private static final int MAX_STEPS_LIMIT = 5;
    private static final Set<PlannerTaskType> ALLOWED_TASK_TYPES = EnumSet.of(
        PlannerTaskType.SMALL_TALK,
        PlannerTaskType.GENERAL_QA,
        PlannerTaskType.READING_QA,
        PlannerTaskType.MEMORY_QA,
        PlannerTaskType.NOTE_QA,
        PlannerTaskType.READING_PLAN,
        PlannerTaskType.TOOL_ACTION);

    private final ToolRegistry toolRegistry;
    private final ExternalMcpClientService externalMcpClientService;

    public PlanValidator(ToolRegistry toolRegistry, ExternalMcpClientService externalMcpClientService) {
        this.toolRegistry = toolRegistry;
        this.externalMcpClientService = externalMcpClientService;
    }

    public ChatPlan validateAndConvert(LlmPlanResponse response, String originalQuestion) {
        if (response == null) {
            throw new PlanValidationException("LLM plan is null");
        }
        PlannerTaskType taskType = parseEnum(PlannerTaskType.class, response.taskType(), "taskType");
        if (!ALLOWED_TASK_TYPES.contains(taskType)) {
            throw new PlanValidationException("taskType is not allowed: " + taskType);
        }
        ToolExecutionMode executionMode = parseEnum(ToolExecutionMode.class, response.executionMode(), "executionMode");
        SubIntent subIntent = parseEnum(SubIntent.class, response.subIntent(), "subIntent");
        AnswerMode answerMode = parseEnum(AnswerMode.class, response.answerMode(), "answerMode");
        EvidenceStrictness evidenceStrictness = parseEnum(
            EvidenceStrictness.class,
            response.evidenceStrictness(),
            "evidenceStrictness");
        LlmAnswerRequirement llmRequirement = response.answerRequirement();
        if (llmRequirement == null || !llmRequirement.complete()) {
            throw new PlanValidationException("answerRequirement is incomplete");
        }
        DetailLevel.valueOf(llmRequirement.minDetailLevel());

        String standaloneQuestion = requiredText(response.standaloneQuestion(), "standaloneQuestion");
        boolean dependsOnContext = response.dependsOnContext() != null && response.dependsOnContext();
        int maxSteps = response.maxSteps() == null ? -1 : response.maxSteps();
        if (maxSteps < 0 || maxSteps > MAX_STEPS_LIMIT) {
            throw new PlanValidationException("maxSteps out of range: " + maxSteps);
        }

        List<String> allowedTools = response.allowedTools();
        List<LlmToolStep> toolPlan = response.toolPlan();
        validateMode(executionMode, maxSteps, toolPlan);
        validateIntentConsistency(originalQuestion, standaloneQuestion, taskType, answerMode, dependsOnContext,
            allowedTools, toolPlan);
        validateTools(executionMode, allowedTools, toolPlan);

        List<ToolStep> steps = toolPlan.stream()
            .map(step -> new ToolStep(step.toolName(), step.arguments(), step.reason()))
            .toList();
        return new ChatPlan(
            originalQuestion,
            standaloneQuestion,
            requiredText(response.planningReason(), "planningReason"),
            taskType,
            subIntent,
            llmRequirement.toAnswerRequirement(),
            answerMode,
            evidenceStrictness,
            dependsOnContext,
            executionMode,
            allowedTools,
            steps,
            requiredText(response.taskGoal(), "taskGoal"),
            maxSteps,
            requiredText(response.stopCondition(), "stopCondition"),
            requiredText(response.answerGuidance(), "answerGuidance"));
    }

    private void validateMode(ToolExecutionMode mode, int maxSteps, List<LlmToolStep> toolPlan) {
        if (toolPlan.size() > maxSteps) {
            throw new PlanValidationException("toolPlan size exceeds maxSteps");
        }
        switch (mode) {
            case NO_TOOL -> {
                if (!toolPlan.isEmpty()) {
                    throw new PlanValidationException("NO_TOOL requires empty toolPlan");
                }
            }
            case SINGLE_TOOL -> {
                if (toolPlan.size() != 1) {
                    throw new PlanValidationException("SINGLE_TOOL requires exactly one step");
                }
            }
            case MULTI_TOOL -> {
                if (toolPlan.isEmpty()) {
                    throw new PlanValidationException("MULTI_TOOL requires at least one step");
                }
            }
            case BOUNDED_REACT -> {
                if (!toolPlan.isEmpty()) {
                    throw new PlanValidationException("BOUNDED_REACT requires empty toolPlan; ReAct agent selects server tools later");
                }
            }
        }
    }

    private void validateTools(ToolExecutionMode executionMode, List<String> allowedTools, List<LlmToolStep> toolPlan) {
        Set<String> allowedSet = new HashSet<>(allowedTools);
        if (allowedSet.size() != allowedTools.size()) {
            throw new PlanValidationException("allowedTools contains duplicates");
        }
        long serverTokens = allowedTools.stream().filter(this::isMcpServerToken).count();
        if (executionMode == ToolExecutionMode.BOUNDED_REACT && serverTokens != 1) {
            throw new PlanValidationException("BOUNDED_REACT requires exactly one mcp.server token");
        }
        if (serverTokens > 0 && (serverTokens != 1 || allowedTools.size() != 1)) {
            throw new PlanValidationException("MCP server plans must allow exactly one mcp.server token");
        }
        for (String toolName : allowedTools) {
            if (isMcpServerToken(toolName)) {
                validateEnabledMcpServer(toolName);
            } else {
                validateEnabledTool(toolName);
            }
        }
        for (LlmToolStep step : toolPlan) {
            String toolName = requiredText(step.toolName(), "toolPlan.toolName");
            if (!allowedSet.contains(toolName)) {
                throw new PlanValidationException("toolPlan tool is not in allowedTools: " + toolName);
            }
            AvailableTool tool = validateEnabledTool(toolName);
            if (tool.writeOperation() || tool.requiresConfirmation()) {
                throw new PlanValidationException("write/confirmation tool cannot be executed directly: " + toolName);
            }
        }
    }

    private void validateIntentConsistency(String originalQuestion,
                                           String standaloneQuestion,
                                           PlannerTaskType taskType,
                                           AnswerMode answerMode,
                                           boolean dependsOnContext,
                                           List<String> allowedTools,
                                           List<LlmToolStep> toolPlan) {
        boolean externalRequired = PlannerIntentClassifier.requiresUnavailableExternalTool(originalQuestion)
            || PlannerIntentClassifier.requiresUnavailableExternalTool(standaloneQuestion);
        if (externalRequired) {
            if (usesAnyTool(toolPlan, "rag.search", "memory.search", "context.get_current_page")) {
                throw new PlanValidationException("external/realtime request cannot be answered by reading or memory tools");
            }
            if (allowedTools.stream().anyMatch("mcp.server:self-local"::equals)) {
                throw new PlanValidationException("external/realtime request cannot use self-local MCP server");
            }
        }
        if (PlannerIntentClassifier.requiresRealtimeExternalFact(originalQuestion) && answerMode == AnswerMode.TEXT_ONLY) {
            throw new PlanValidationException("realtime external fact request cannot use TEXT_ONLY mode");
        }
        if (usesAnyTool(toolPlan, "rag.search")
            && !PlannerIntentClassifier.readingRelated(originalQuestion, standaloneQuestion, taskType)) {
            throw new PlanValidationException("rag.search is only allowed for reading/book evidence questions");
        }
        if (!dependsOnContext && usesAnyTool(toolPlan, "context.get_recent_dialogue", "context.get_current_page")) {
            throw new PlanValidationException("context tools require dependsOnContext=true");
        }
    }

    private boolean usesAnyTool(List<LlmToolStep> toolPlan, String... toolNames) {
        Set<String> expected = Set.of(toolNames);
        return toolPlan.stream()
            .map(LlmToolStep::toolName)
            .anyMatch(expected::contains);
    }

    private AvailableTool validateEnabledTool(String toolName) {
        String normalized = requiredText(toolName, "toolName");
        if (normalized.startsWith("external.")) {
            throw new PlanValidationException("external MCP tool is not enabled: " + normalized);
        }
        return toolRegistry.enabledTool(normalized)
            .orElseThrow(() -> new PlanValidationException("tool is not enabled: " + normalized));
    }

    private void validateEnabledMcpServer(String serverToken) {
        String serverName = serverToken.substring("mcp.server:".length()).trim();
        if (serverName.isBlank()) {
            throw new PlanValidationException("mcp.server token requires server name");
        }
        Set<String> routableServers = externalMcpClientService.routableServers().stream()
            .map(server -> String.valueOf(server.get("name")).trim())
            .filter(name -> !name.isBlank())
            .collect(Collectors.toSet());
        if (!routableServers.contains(serverName)) {
            throw new PlanValidationException("MCP server is not enabled or routable: " + serverName);
        }
    }

    private boolean isMcpServerToken(String value) {
        return value != null && value.startsWith("mcp.server:");
    }

    private String requiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new PlanValidationException(fieldName + " is required");
        }
        return value.trim();
    }

    private <T extends Enum<T>> T parseEnum(Class<T> enumType, String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new PlanValidationException(fieldName + " is required");
        }
        try {
            return Enum.valueOf(enumType, value.trim());
        } catch (IllegalArgumentException ex) {
            throw new PlanValidationException(fieldName + " is invalid: " + value);
        }
    }
}
