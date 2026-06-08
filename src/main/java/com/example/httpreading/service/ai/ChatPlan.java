package com.example.httpreading.service.ai;

import java.util.List;

public record ChatPlan(String originalQuestion,
                       String standaloneQuestion,
                       String planningReason,
                       PlannerTaskType taskType,
                       boolean dependsOnContext,
                       ToolExecutionMode executionMode,
                       List<String> allowedTools,
                       List<ToolStep> toolPlan,
                       String taskGoal,
                       int maxSteps,
                       String stopCondition,
                       String answerGuidance) {
    public ChatPlan {
        originalQuestion = originalQuestion == null ? "" : originalQuestion;
        standaloneQuestion = standaloneQuestion == null || standaloneQuestion.isBlank()
            ? originalQuestion
            : standaloneQuestion;
        planningReason = planningReason == null ? "" : planningReason;
        taskType = taskType == null ? PlannerTaskType.UNKNOWN : taskType;
        executionMode = executionMode == null ? ToolExecutionMode.NO_TOOL : executionMode;
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        toolPlan = toolPlan == null ? List.of() : List.copyOf(toolPlan);
        taskGoal = taskGoal == null ? "" : taskGoal;
        maxSteps = maxSteps <= 0 ? 1 : maxSteps;
        stopCondition = stopCondition == null ? "" : stopCondition;
        answerGuidance = answerGuidance == null ? "" : answerGuidance;
    }
}
