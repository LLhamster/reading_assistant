package com.example.httpreading.evolution;

import java.util.List;

public record EvolutionIterationResult(int iteration,
                                       PromptOverride prompt,
                                       List<EvolutionCaseResult> results,
                                       SelfEvolutionReport.Aggregate aggregate,
                                       boolean valid,
                                       boolean safetyPassed,
                                       boolean beatsBaseline,
                                       List<String> fixedCaseIds,
                                       List<String> persistentFailureCaseIds,
                                       List<String> regressionCaseIds) {
    public EvolutionIterationResult {
        iteration = Math.max(1, iteration);
        prompt = prompt == null ? PromptOverride.none() : prompt;
        results = results == null ? List.of() : List.copyOf(results);
        aggregate = aggregate == null
            ? new SelfEvolutionReport.Aggregate(0, 0, 0, 0.0, 0.0)
            : aggregate;
        fixedCaseIds = fixedCaseIds == null ? List.of() : List.copyOf(fixedCaseIds);
        persistentFailureCaseIds = persistentFailureCaseIds == null
            ? List.of()
            : List.copyOf(persistentFailureCaseIds);
        regressionCaseIds = regressionCaseIds == null
            ? List.of()
            : List.copyOf(regressionCaseIds);
    }
}
