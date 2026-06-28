package com.example.httpreading.evolution;

public record EvolutionExecutionOptions(boolean evalMode,
                                        boolean disableMemoryWrite,
                                        PromptOverride promptOverride) {
    public EvolutionExecutionOptions {
        promptOverride = promptOverride == null ? PromptOverride.none() : promptOverride;
    }

    public static EvolutionExecutionOptions production() {
        return new EvolutionExecutionOptions(false, false, PromptOverride.none());
    }

    public static EvolutionExecutionOptions evaluation(PromptOverride promptOverride) {
        return new EvolutionExecutionOptions(true, true, promptOverride);
    }
}
