package com.example.httpreading.evolution;

public record SelfEvolutionRunOptions(int caseCount,
                                      int maxCandidateIterations,
                                      boolean deterministicEvaluation) {
    public static final int DEFAULT_MAX_CANDIDATE_ITERATIONS = 3;

    public SelfEvolutionRunOptions {
        caseCount = Math.max(1, Math.min(EvalCaseGenerator.DEFAULT_CASE_COUNT, caseCount));
        maxCandidateIterations = Math.max(1, Math.min(5, maxCandidateIterations));
    }

    public static SelfEvolutionRunOptions defaults() {
        return new SelfEvolutionRunOptions(
            EvalCaseGenerator.DEFAULT_CASE_COUNT,
            DEFAULT_MAX_CANDIDATE_ITERATIONS,
            true);
    }

    public static SelfEvolutionRunOptions forCaseCount(int caseCount) {
        return new SelfEvolutionRunOptions(
            caseCount,
            DEFAULT_MAX_CANDIDATE_ITERATIONS,
            true);
    }
}
