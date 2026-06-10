package com.example.httpreading.service.ai;

import java.util.List;

public record ChatPlan(String originalQuestion,
                       String standaloneQuestion,
                       String planningReason,
                       PlannerTaskType taskType,
                       SubIntent subIntent,
                       AnswerRequirement answerRequirement,
                       AnswerMode answerMode,
                       EvidenceStrictness evidenceStrictness,
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
        subIntent = subIntent == null ? SubIntent.NONE : subIntent;
        answerRequirement = answerRequirement == null ? AnswerRequirement.normal() : answerRequirement;
        answerMode = answerMode == null ? AnswerMode.TEXT_ONLY : answerMode;
        evidenceStrictness = evidenceStrictness == null ? EvidenceStrictness.STRICT : evidenceStrictness;
        executionMode = executionMode == null ? ToolExecutionMode.NO_TOOL : executionMode;
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        toolPlan = toolPlan == null ? List.of() : List.copyOf(toolPlan);
        taskGoal = taskGoal == null ? "" : taskGoal;
        maxSteps = Math.max(0, maxSteps);
        stopCondition = stopCondition == null ? "" : stopCondition;
        answerGuidance = answerGuidance == null ? "" : answerGuidance;
    }

    public ChatPlan(String originalQuestion,
                    String standaloneQuestion,
                    String planningReason,
                    PlannerTaskType taskType,
                    SubIntent subIntent,
                    AnswerRequirement answerRequirement,
                    boolean dependsOnContext,
                    ToolExecutionMode executionMode,
                    List<String> allowedTools,
                    List<ToolStep> toolPlan,
                    String taskGoal,
                    int maxSteps,
                    String stopCondition,
                    String answerGuidance) {
        this(
            originalQuestion,
            standaloneQuestion,
            planningReason,
            taskType,
            subIntent,
            answerRequirement,
            AnswerMode.TEXT_ONLY,
            EvidenceStrictness.STRICT,
            dependsOnContext,
            executionMode,
            allowedTools,
            toolPlan,
            taskGoal,
            maxSteps,
            stopCondition,
            answerGuidance);
    }

    public ChatPlan(String originalQuestion,
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
        this(
            originalQuestion,
            standaloneQuestion,
            planningReason,
            taskType,
            SubIntent.NONE,
            AnswerRequirement.normal(),
            AnswerMode.TEXT_ONLY,
            EvidenceStrictness.STRICT,
            dependsOnContext,
            executionMode,
            allowedTools,
            toolPlan,
            taskGoal,
            maxSteps,
            stopCondition,
            answerGuidance);
    }
}
