package com.example.httpreading.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EvolvablePromptTemplateTest {
    @Test
    void candidateCanOnlyBeAppendedInsideEvolvableSlot() {
        EvolvablePromptTemplate template =
            new EvolvablePromptTemplate("固定角色和输出格式", "默认回答策略");

        String baseline = template.render("");
        String candidate = template.render("先给结论");

        assertTrue(candidate.contains("[FIXED_PROMPT_CONTRACT_BEGIN]\n固定角色和输出格式"));
        assertTrue(candidate.contains("默认回答策略\n\n候选策略（仅本次实验生效）：\n先给结论"));
        assertEquals(
            baseline.substring(
                baseline.indexOf("[FIXED_PROMPT_CONTRACT_BEGIN]"),
                baseline.indexOf("[FIXED_PROMPT_CONTRACT_END]")),
            candidate.substring(
                candidate.indexOf("[FIXED_PROMPT_CONTRACT_BEGIN]"),
                candidate.indexOf("[FIXED_PROMPT_CONTRACT_END]")));
    }
}
