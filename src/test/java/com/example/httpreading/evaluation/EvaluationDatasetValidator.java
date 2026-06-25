package com.example.httpreading.evaluation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.example.httpreading.service.ai.ToolExecutionMode;
import com.example.httpreading.service.ai.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

final class EvaluationDatasetValidator {
    private static final Set<String> SUITES = Set.of(EvaluationCases.TOOL_ROUTING, EvaluationCases.MULTI_TURN_QA);
    private static final Set<String> DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD");
    private static final Set<String> SOURCES = Set.of("golden", "synthetic", "sessiondb");
    private static final Set<String> SPLITS = Set.of(EvaluationCases.DEV, EvaluationCases.HOLDOUT);
    private static final Set<String> REAL_CASE_REQUIRED = Set.of(
        "人物", "具体时间", "事件背景", "处理过程", "结果", "和用户问题的对应关系");
    private static final Set<String> INSUFFICIENT_EVIDENCE_REQUIRED = Set.of(
        "说明证据不足", "说明现有证据只能支持什么");
    private static final Set<String> COMPLETE_STORY_REQUIRED = Set.of("背景", "过程", "结果");
    private static final Pattern SECRET = Pattern.compile(
        "(?i)(sk-[a-z0-9_-]{16,}|bearer\\s+[a-z0-9._-]{16,}|api[_-]?key\\s*[:=]\\s*[^,\\s]{12,})");
    private static final Pattern EMAIL = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;
    private final Set<String> enabledTools;

    EvaluationDatasetValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        Set<String> tools = new HashSet<>(new ToolRegistry().enabledTools().stream().map(tool -> tool.name()).toList());
        tools.add("mcp.server:self-local");
        enabledTools = Set.copyOf(tools);
    }

    List<String> validate(List<EvaluationCases.EvaluationExample> examples) {
        Set<String> ids = new HashSet<>();
        java.util.ArrayList<String> failures = new java.util.ArrayList<>();
        for (EvaluationCases.EvaluationExample example : examples) {
            String id = example.id().isBlank() ? "<blank>" : example.id();
            require(!example.id().isBlank(), id, "id is blank", failures);
            require(ids.add(example.id()), id, "duplicate id", failures);
            require(SUITES.contains(example.suite()), id, "invalid suite", failures);
            require(DIFFICULTIES.contains(example.difficulty()), id, "invalid difficulty", failures);
            require(SOURCES.contains(example.source()), id, "invalid source", failures);
            require(SPLITS.contains(example.split()), id, "invalid split", failures);
            require("golden".equals(example.source()) || EvaluationCases.DEV.equals(example.split()),
                id, "synthetic/sessiondb candidates must stay in dev", failures);
            require(example.taskInput() != null && !example.taskInput().question().isBlank(), id, "question is blank", failures);
            validateSuiteShape(example, id, failures);
            detectSensitiveContent(example, id, failures);
        }
        return List.copyOf(failures);
    }

    private void validateSuiteShape(EvaluationCases.EvaluationExample example, String id, List<String> failures) {
        if (EvaluationCases.TOOL_ROUTING.equals(example.suite())) {
            require(example.expectedResult() != null, id, "tool routing requires expected_result", failures);
            require(example.expectedBehavior() == null, id, "tool routing must not contain expected_behavior", failures);
            if (example.expectedResult() != null) {
                require(example.expectedResult().localTools().stream().allMatch(enabledTools::contains),
                    id, "contains unknown tool", failures);
                try {
                    ToolExecutionMode.valueOf(example.expectedResult().plannerMode());
                } catch (IllegalArgumentException exception) {
                    failures.add(id + ": invalid planner mode");
                }
            }
            return;
        }
        require(example.expectedResult() == null, id, "answer case must not contain expected_result", failures);
        require(example.expectedBehavior() != null && !example.expectedBehavior().scoringCriteria().isEmpty(),
            id, "answer case requires scoring criteria", failures);
        if (example.expectedBehavior() != null) validateBehavior(example, failures);
    }

    private void validateBehavior(EvaluationCases.EvaluationExample example, List<String> failures) {
        String id = example.id();
        EvaluationCases.ExpectedBehavior expected = example.expectedBehavior();
        require(example.taskInput().dialogue().size() >= 2, id, "multi-turn case needs dialogue", failures);
        require(!example.taskInput().collectedEvidence().isEmpty(), id, "collected_evidence is empty", failures);
        require(!example.taskInput().mcpResults().isEmpty(), id, "mcp_results is empty", failures);
        Set<String> criterionIds = new HashSet<>();
        require(expected.scoringCriteria().stream().allMatch(criterion -> !criterion.id().isBlank()
            && !criterion.description().isBlank() && criterion.score() > 0 && criterionIds.add(criterion.id())),
            id, "criteria need unique id, description and positive score", failures);
        double scoreSum = expected.scoringCriteria().stream().mapToDouble(EvaluationCases.ScoringCriterion::score).sum();
        require(Math.abs(scoreSum - expected.maxScore()) < 0.0001, id, "max_score does not match criteria", failures);
        require(expected.evidencePolicy() != null, id, "evidence_policy is missing", failures);
        require(expected.mustInclude() != null, id, "must_include is null", failures);
        require(expected.mustNotInclude() != null, id, "must_not_include is null", failures);
        require(expected.styleConstraints() != null, id, "style_constraints is null", failures);
        require(expected.maxChars() > 0, id, "max_chars must be positive", failures);
        validateCategorySpecificBehavior(example, failures);
    }

    private void validateCategorySpecificBehavior(EvaluationCases.EvaluationExample example, List<String> failures) {
        EvaluationCases.ExpectedBehavior expected = example.expectedBehavior();
        String category = example.category();
        if ("REAL_CASE_REQUEST".equals(category)) {
            require(containsAll(expected.mustInclude(), REAL_CASE_REQUIRED), example.id(),
                "REAL_CASE_REQUEST must_include misses required story case fields", failures);
        }
        if ("STYLE_CONTROL".equals(category)) {
            require(!expected.styleConstraints().isEmpty() || !expected.mustNotInclude().isEmpty(), example.id(),
                "STYLE_CONTROL requires style_constraints or must_not_include", failures);
        }
        if ("INSUFFICIENT_EVIDENCE".equals(category)) {
            require(containsAll(expected.mustInclude(), INSUFFICIENT_EVIDENCE_REQUIRED), example.id(),
                "INSUFFICIENT_EVIDENCE must_include must require evidence insufficiency explanation", failures);
        }
        if ("COMPLETE_STORY_REQUEST".equals(category)) {
            require(containsAll(expected.mustInclude(), COMPLETE_STORY_REQUIRED), example.id(),
                "COMPLETE_STORY_REQUEST must_include misses background/process/result", failures);
        }
    }

    private boolean containsAll(List<String> actual, Set<String> required) {
        return required.stream().allMatch(requiredItem -> actual.stream().anyMatch(value -> value.contains(requiredItem)));
    }

    private void detectSensitiveContent(EvaluationCases.EvaluationExample example, String id, List<String> failures) {
        try {
            String json = objectMapper.writeValueAsString(example);
            require(!SECRET.matcher(json).find(), id, "possible API key/token detected", failures);
            require(!EMAIL.matcher(json).find(), id, "possible email detected", failures);
        } catch (JsonProcessingException exception) {
            failures.add(id + ": cannot inspect JSON: " + exception.getMessage());
        }
    }

    private void require(boolean condition, String id, String message, List<String> failures) {
        if (!condition) {
            failures.add(id + ": " + message);
        }
    }
}
