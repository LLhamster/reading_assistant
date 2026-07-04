package com.example.httpreading.evolution;

/**
 * FinalAnswer-only reading boundaries. Retrieval and planner correctness belong
 * to separate suites; these boundaries assume the evidence is already fixed.
 */
public enum ReadingBoundary {
    DIRECT_TEXT_FACT,
    SOURCE_QUOTATION,
    INSUFFICIENT_EVIDENCE,
    CONFLICTING_EVIDENCE,
    CONCEPT_EXPLANATION,
    CAUSAL_REASONING,
    PEDAGOGICAL_EXAMPLE,
    SOURCE_NARRATIVE,
    MULTI_TURN_FOLLOW_UP,
    MEMORY_TEXT_SEPARATION
}
