package com.example.httpreading.evolution;

import java.util.List;
import java.util.Map;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.service.ai.AnswerMode;
import com.example.httpreading.service.ai.AnswerRequirement;
import com.example.httpreading.service.ai.EvidenceStrictness;
import com.example.httpreading.service.ai.SubIntent;

public record EvolutionEvalCase(String id,
                                String signalId,
                                AiChatRequest request,
                                FailureType expectedFailureType,
                                List<String> anchorTerms,
                                int minimumAnswerChars,
                                String previousAnswer,
                                List<DialogueTurn> dialogue,
                                List<CollectedEvidence> collectedEvidence,
                                List<McpResult> mcpResults,
                                FinalAnswerInput finalAnswerInput,
                                ExpectedBehavior expectedBehavior,
                                ReadingBoundarySpec boundarySpec,
                                String difficulty,
                                String category) {
    public EvolutionEvalCase {
        id = safe(id);
        signalId = safe(signalId);
        expectedFailureType = expectedFailureType == null ? FailureType.NOT_DIRECT : expectedFailureType;
        anchorTerms = anchorTerms == null ? List.of() : List.copyOf(anchorTerms);
        minimumAnswerChars = Math.max(0, minimumAnswerChars);
        previousAnswer = safe(previousAnswer);
        dialogue = dialogue == null ? List.of() : List.copyOf(dialogue);
        collectedEvidence = collectedEvidence == null ? List.of() : List.copyOf(collectedEvidence);
        mcpResults = mcpResults == null ? List.of() : List.copyOf(mcpResults);
        finalAnswerInput = finalAnswerInput == null
            ? FinalAnswerInput.defaultFor(request)
            : finalAnswerInput;
        expectedBehavior = expectedBehavior == null ? ExpectedBehavior.empty() : expectedBehavior;
        boundarySpec = boundarySpec == null
            ? ReadingBoundarySpec.defaultSpec()
            : boundarySpec;
        difficulty = safe(difficulty);
        category = safe(category);
    }

    public EvolutionEvalCase(String id,
                             String signalId,
                             AiChatRequest request,
                             FailureType expectedFailureType,
                             List<String> anchorTerms,
                             int minimumAnswerChars,
                             String previousAnswer) {
        this(id, signalId, request, expectedFailureType, anchorTerms, minimumAnswerChars, previousAnswer,
            List.of(), List.of(), List.of(), FinalAnswerInput.defaultFor(request),
            ExpectedBehavior.empty(), ReadingBoundarySpec.defaultSpec(),
            "MEDIUM", expectedFailureType.name());
    }

    public record DialogueTurn(String role, String content) {
        public DialogueTurn {
            role = safe(role);
            content = safe(content);
        }
    }

    public record CollectedEvidence(String id,
                                    String type,
                                    String title,
                                    String content,
                                    Map<String, Object> metadata) {
        public CollectedEvidence {
            id = safe(id);
            type = safe(type);
            title = safe(title);
            content = safe(content);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    public record McpResult(String tool, boolean ok, Map<String, Object> data) {
        public McpResult {
            tool = safe(tool);
            data = data == null ? Map.of() : Map.copyOf(data);
        }
    }

    public record ScoringCriterion(String id, String description, double score) {
        public ScoringCriterion {
            id = safe(id);
            description = safe(description);
        }
    }

    public record EvidencePolicy(boolean useProvidedEvidence,
                                 boolean allowGeneralExplanation,
                                 EvidenceUseMode evidenceUseMode) {
        public EvidencePolicy {
            evidenceUseMode = evidenceUseMode == null
                ? EvidenceUseMode.STRICT_SOURCE
                : evidenceUseMode;
        }
    }

    public record FinalAnswerInput(String standaloneQuestion,
                                   SubIntent subIntent,
                                   AnswerRequirement answerRequirement,
                                   AnswerMode answerMode,
                                   EvidenceStrictness evidenceStrictness,
                                   boolean dependsOnContext,
                                   String answerGuidance) {
        public FinalAnswerInput {
            standaloneQuestion = safe(standaloneQuestion);
            subIntent = subIntent == null ? SubIntent.NONE : subIntent;
            answerRequirement = answerRequirement == null
                ? AnswerRequirement.normal()
                : answerRequirement;
            answerMode = answerMode == null ? AnswerMode.TEXT_ONLY : answerMode;
            evidenceStrictness = evidenceStrictness == null
                ? EvidenceStrictness.STRICT
                : evidenceStrictness;
            answerGuidance = safe(answerGuidance);
        }

        static FinalAnswerInput defaultFor(AiChatRequest request) {
            String question = request == null ? "" : request.getQuestion();
            return new FinalAnswerInput(
                question, SubIntent.NONE, AnswerRequirement.normal(),
                AnswerMode.TEXT_ONLY, EvidenceStrictness.STRICT, true, "");
        }
    }

    public record ExpectedBehavior(List<ScoringCriterion> scoringCriteria,
                                   double maxScore,
                                   EvidencePolicy evidencePolicy,
                                   int maxChars) {
        public ExpectedBehavior {
            scoringCriteria = scoringCriteria == null ? List.of() : List.copyOf(scoringCriteria);
            evidencePolicy = evidencePolicy == null
                ? new EvidencePolicy(true, true, EvidenceUseMode.STRICT_SOURCE)
                : evidencePolicy;
        }

        static ExpectedBehavior empty() {
            return new ExpectedBehavior(List.of(), 0.0,
                new EvidencePolicy(true, true, EvidenceUseMode.STRICT_SOURCE), 500);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
