package com.example.httpreading.evolution;

import java.util.List;

public record SelfEvolutionReport(String runId,
                                  String userId,
                                  List<MisunderstandingSignal> signals,
                                  List<EvolutionEvalCase> evalCases,
                                  List<EvolutionCaseResult> baselineResults,
                                  List<EvolutionCaseResult> candidateResults,
                                  PromptOverride candidatePrompt,
                                  Aggregate baseline,
                                  Aggregate candidate,
                                  boolean experimentValid,
                                  boolean candidateBetter,
                                  String recommendation,
                                  List<EvolutionIterationResult> iterations,
                                  Integer selectedIteration,
                                  String stopReason) {
    public SelfEvolutionReport {
        signals = signals == null ? List.of() : List.copyOf(signals);
        evalCases = evalCases == null ? List.of() : List.copyOf(evalCases);
        baselineResults = baselineResults == null ? List.of() : List.copyOf(baselineResults);
        candidateResults = candidateResults == null ? List.of() : List.copyOf(candidateResults);
        candidatePrompt = candidatePrompt == null ? PromptOverride.none() : candidatePrompt;
        recommendation = recommendation == null ? "" : recommendation;
        iterations = iterations == null ? List.of() : List.copyOf(iterations);
        stopReason = stopReason == null ? "" : stopReason;
    }

    public SelfEvolutionReport(String runId,
                               String userId,
                               List<MisunderstandingSignal> signals,
                               List<EvolutionEvalCase> evalCases,
                               List<EvolutionCaseResult> baselineResults,
                               List<EvolutionCaseResult> candidateResults,
                               PromptOverride candidatePrompt,
                               Aggregate baseline,
                               Aggregate candidate,
                               boolean experimentValid,
                               boolean candidateBetter,
                               String recommendation) {
        this(runId, userId, signals, evalCases, baselineResults, candidateResults,
            candidatePrompt, baseline, candidate, experimentValid, candidateBetter,
            recommendation, List.of(), null, "");
    }

    public record Aggregate(int evaluated,
                            int passed,
                            int hardFailures,
                            double averageScore,
                            double passRate) {
    }
}
