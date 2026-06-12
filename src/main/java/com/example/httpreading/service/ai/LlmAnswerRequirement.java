package com.example.httpreading.service.ai;

public record LlmAnswerRequirement(Boolean requiresConcreteExample,
                                   Boolean requiresSpecificEntity,
                                   Boolean requiresStorytelling,
                                   Boolean requiresDetailedProcess,
                                   Boolean avoidConceptualOpening,
                                   Boolean avoidRepeatingPreviousExplanation,
                                   Boolean allowModelKnowledge,
                                   Boolean mustDistinguishTextEvidenceAndSupplement,
                                   Boolean avoidRepeatingSourcePhrases,
                                   String minDetailLevel) {
    boolean complete() {
        return requiresConcreteExample != null
            && requiresSpecificEntity != null
            && requiresStorytelling != null
            && requiresDetailedProcess != null
            && avoidConceptualOpening != null
            && avoidRepeatingPreviousExplanation != null
            && allowModelKnowledge != null
            && mustDistinguishTextEvidenceAndSupplement != null
            && avoidRepeatingSourcePhrases != null
            && minDetailLevel != null
            && !minDetailLevel.isBlank();
    }

    AnswerRequirement toAnswerRequirement() {
        return new AnswerRequirement(
            Boolean.TRUE.equals(requiresConcreteExample),
            Boolean.TRUE.equals(requiresSpecificEntity),
            Boolean.TRUE.equals(requiresStorytelling),
            Boolean.TRUE.equals(requiresDetailedProcess),
            Boolean.TRUE.equals(avoidConceptualOpening),
            Boolean.TRUE.equals(avoidRepeatingPreviousExplanation),
            Boolean.TRUE.equals(allowModelKnowledge),
            Boolean.TRUE.equals(mustDistinguishTextEvidenceAndSupplement),
            Boolean.TRUE.equals(avoidRepeatingSourcePhrases),
            parseDetailLevel(minDetailLevel));
    }

    private DetailLevel parseDetailLevel(String value) {
        if (value == null || value.isBlank()) {
            return DetailLevel.MEDIUM;
        }
        try {
            return DetailLevel.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            return DetailLevel.MEDIUM;
        }
    }
}
