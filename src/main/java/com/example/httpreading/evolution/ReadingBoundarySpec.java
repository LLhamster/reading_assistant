package com.example.httpreading.evolution;

public record ReadingBoundarySpec(ReadingBoundary boundary,
                                  EvidenceCompleteness evidenceCompleteness,
                                  ConversationState conversationState) {
    public ReadingBoundarySpec {
        boundary = boundary == null ? ReadingBoundary.DIRECT_TEXT_FACT : boundary;
        evidenceCompleteness = evidenceCompleteness == null
            ? EvidenceCompleteness.COMPLETE
            : evidenceCompleteness;
        conversationState = conversationState == null
            ? ConversationState.SINGLE_TURN
            : conversationState;
    }

    public static ReadingBoundarySpec defaultSpec() {
        return new ReadingBoundarySpec(
            ReadingBoundary.DIRECT_TEXT_FACT,
            EvidenceCompleteness.COMPLETE,
            ConversationState.SINGLE_TURN);
    }
}
