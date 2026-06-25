package com.example.httpreading.evaluation;

import java.util.List;
import java.util.Map;

final class EvaluationCases {
    static final String TOOL_ROUTING = "TOOL_ROUTING";
    static final String MULTI_TURN_QA = "MULTI_TURN_QA";
    static final String DEV = "dev";
    static final String HOLDOUT = "holdout";
    static final String ALL = "all";

    private EvaluationCases() {
    }

    record EvaluationExample(
        String id,
        String suite,
        TaskInput taskInput,
        ExpectedResult expectedResult,
        ExpectedBehavior expectedBehavior,
        String difficulty,
        String category,
        String source,
        String split,
        Map<String, Object> provenance) {
        EvaluationExample {
            id = safe(id);
            suite = safe(suite);
            difficulty = safe(difficulty);
            category = safe(category);
            source = safe(source);
            split = safe(split);
            provenance = provenance == null ? Map.of() : Map.copyOf(provenance);
        }
    }

    record TaskInput(
        String question,
        RoutingContext context,
        BookContext readingContext,
        List<DialogueTurn> dialogue,
        List<CollectedEvidence> collectedEvidence,
        List<McpResult> mcpResults) {
        TaskInput {
            question = safe(question);
            dialogue = dialogue == null ? List.of() : List.copyOf(dialogue);
            collectedEvidence = collectedEvidence == null ? List.of() : List.copyOf(collectedEvidence);
            mcpResults = mcpResults == null ? List.of() : List.copyOf(mcpResults);
        }
    }

    /** Fixed gold label for tool routing; it is scored deterministically, without LLM Judge. */
    record ExpectedResult(String plannerMode, String plannerServer, List<String> localTools) {
        ExpectedResult {
            plannerMode = safe(plannerMode);
            plannerServer = safe(plannerServer);
            localTools = copy(localTools);
        }
    }

    /** Case-specific final-answer rubric over already collected evidence and MCP results. */
    record ExpectedBehavior(
        List<ScoringCriterion> scoringCriteria,
        double maxScore,
        EvidencePolicy evidencePolicy,
        int maxChars) {
        ExpectedBehavior {
            scoringCriteria = scoringCriteria == null ? List.of() : List.copyOf(scoringCriteria);
        }
    }

    record ScoringCriterion(String id, String description, double score) {
        ScoringCriterion {
            id = safe(id);
            description = safe(description);
        }
    }

    record EvidencePolicy(boolean useProvidedEvidence,
                          boolean allowGeneralExplanation,
                          boolean allowHypotheticalExample,
                          boolean mustLabelHypotheticalExample) {
    }

    record RoutingContext(
        String userId,
        String sessionId,
        Long bookId,
        Integer chapterIndex,
        String chapterTitle,
        String selectedText,
        String selectedContext,
        Boolean memoryEnabled,
        Boolean ragEnabled) {
    }

    record BookContext(String title, String chapter) {
    }

    record DialogueTurn(String role, String content) {
    }

    record CollectedEvidence(String id, String type, String title, String content, Map<String, Object> metadata) {
        CollectedEvidence {
            id = safe(id);
            type = safe(type);
            title = safe(title);
            content = safe(content);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    record McpResult(String tool, boolean ok, Map<String, Object> data) {
        McpResult {
            tool = safe(tool);
            data = data == null ? Map.of() : Map.copyOf(data);
        }
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
