package com.example.httpreading.evolution;

/**
 * A small, domain-independent prompt composition primitive.
 *
 * <p>The fixed contract is always rendered unchanged. Experiments may only append
 * instructions inside the evolvable policy slot.</p>
 */
public record EvolvablePromptTemplate(String fixedContract, String defaultPolicy) {
    public EvolvablePromptTemplate {
        fixedContract = safe(fixedContract);
        defaultPolicy = safe(defaultPolicy);
    }

    public String render(String candidatePatch) {
        String patch = safe(candidatePatch);
        StringBuilder prompt = new StringBuilder()
            .append("[FIXED_PROMPT_CONTRACT_BEGIN]\n")
            .append(fixedContract)
            .append("\n[FIXED_PROMPT_CONTRACT_END]\n\n")
            .append("[EVOLVABLE_PROMPT_POLICY_BEGIN]\n")
            .append(defaultPolicy);
        if (!patch.isBlank()) {
            prompt.append("\n\n候选策略（仅本次实验生效）：\n").append(patch);
        }
        return prompt.append("\n[EVOLVABLE_PROMPT_POLICY_END]").toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
