package com.example.httpreading.service.ai;

public record AnswerRequirement(boolean requiresConcreteExample,
                                boolean requiresSpecificEntity,
                                boolean requiresStorytelling,
                                boolean requiresDetailedProcess,
                                boolean avoidConceptualOpening,
                                boolean avoidRepeatingPreviousExplanation,
                                boolean allowModelKnowledge,
                                boolean mustDistinguishTextEvidenceAndSupplement,
                                boolean avoidRepeatingSourcePhrases,
                                DetailLevel minDetailLevel) {
    public AnswerRequirement {
        minDetailLevel = minDetailLevel == null ? DetailLevel.MEDIUM : minDetailLevel;
    }

    public AnswerRequirement(boolean requiresConcreteExample,
                             boolean requiresSpecificEntity,
                             boolean requiresStorytelling,
                             boolean requiresDetailedProcess,
                             boolean avoidConceptualOpening,
                             boolean avoidRepeatingPreviousExplanation,
                             DetailLevel minDetailLevel) {
        this(
            requiresConcreteExample,
            requiresSpecificEntity,
            requiresStorytelling,
            requiresDetailedProcess,
            avoidConceptualOpening,
            avoidRepeatingPreviousExplanation,
            false,
            false,
            false,
            minDetailLevel);
    }

    public static AnswerRequirement normal() {
        return new AnswerRequirement(false, false, false, false, false, false,
            false, false, false, DetailLevel.MEDIUM);
    }

    public static AnswerRequirement concreteExample() {
        return new AnswerRequirement(true, true, false, false, true, true,
            true, true, true, DetailLevel.MEDIUM);
    }

    public static AnswerRequirement storytellingCase() {
        return new AnswerRequirement(true, true, true, true, true, true,
            true, true, true, DetailLevel.HIGH);
    }

    public static AnswerRequirement understandingAid() {
        return new AnswerRequirement(true, false, false, false, false, true,
            true, true, true, DetailLevel.MEDIUM);
    }
}
