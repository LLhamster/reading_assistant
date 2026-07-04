package com.example.httpreading.evolution;

import java.util.List;

public record CandidateGenerationContext(List<EvolutionEvalCase> cases,
                                         List<EvolutionCaseResult> baselineResults,
                                         List<EvolutionIterationResult> priorIterations) {
    public CandidateGenerationContext {
        cases = cases == null ? List.of() : List.copyOf(cases);
        baselineResults = baselineResults == null ? List.of() : List.copyOf(baselineResults);
        priorIterations = priorIterations == null ? List.of() : List.copyOf(priorIterations);
    }

    public int nextIteration() {
        return priorIterations.size() + 1;
    }

    public EvolutionIterationResult previousIteration() {
        return priorIterations.isEmpty() ? null : priorIterations.get(priorIterations.size() - 1);
    }
}
