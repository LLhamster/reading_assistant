package com.example.httpreading.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.example.httpreading.service.ai.AnswerRequirement;
import com.example.httpreading.service.ai.ChatPlan;
import com.example.httpreading.service.ai.CollectedEvidence;
import com.example.httpreading.service.ai.ToolStep;

final class AiCaseAssertions {
    private AiCaseAssertions() {
    }

    static List<AiCaseFailure> assertPlanMatches(AiCaseSpec spec, ChatPlan plan) {
        List<AiCaseFailure> failures = new ArrayList<>();
        AiCaseSpec.ExpectedPlan expected = spec.expectedPlan;
        if (expected == null) {
            return failures;
        }
        checkEquals(failures, spec, "PLANNER", "taskType", expected.taskType,
            plan.taskType().name());
        checkAnyOf(failures, spec, "PLANNER", "subIntent", expected.subIntentAnyOf,
            expected.subIntent, plan.subIntent().name());
        checkAnyOf(failures, spec, "PLANNER", "executionMode", expected.executionModeAnyOf,
            expected.executionMode, plan.executionMode().name());
        checkEquals(failures, spec, "PLANNER", "answerMode", expected.answerMode,
            plan.answerMode().name());
        checkAnyOf(failures, spec, "PLANNER", "evidenceStrictness", expected.evidenceStrictnessAnyOf,
            expected.evidenceStrictness, plan.evidenceStrictness().name());
        if (expected.allowedTools != null && !expected.allowedTools.equals(plan.allowedTools())) {
            failures.add(failure(spec, "PLANNER", "allowedTools mismatch",
                expected.allowedTools, plan.allowedTools()));
        }
        if (Boolean.TRUE.equals(expected.toolPlanEmpty) && !plan.toolPlan().isEmpty()) {
            failures.add(failure(spec, "PLANNER", "toolPlan should be empty",
                List.of(), plan.toolPlan()));
        }
        checkMustUseAnyTool(failures, spec, plan, expected.mustUseToolsAnyOf);
        checkMustNotUseTools(failures, spec, plan, expected.mustNotUseTools);
        if (expected.answerGuidanceContains != null
            && !plan.answerGuidance().contains(expected.answerGuidanceContains)) {
            failures.add(failure(spec, "PLANNER", "answerGuidance missing text: "
                + expected.answerGuidanceContains, expected.answerGuidanceContains, plan.answerGuidance()));
        }
        checkRequirements(failures, spec, plan.answerRequirement(), expected.requirements);
        return failures;
    }

    static List<AiCaseFailure> assertAnswerMatches(AiCaseSpec spec, String answer, CollectedEvidence evidence) {
        List<AiCaseFailure> failures = new ArrayList<>();
        AiCaseSpec.ExpectedAnswerRules rules = spec.expectedAnswerRules;
        if (rules == null) {
            return failures;
        }
        for (String value : safeList(rules.mustContain)) {
            if (!answer.contains(value)) {
                failures.add(failure(spec, "FINAL_ANSWER", "缺少关键词：" + value, value, answer));
            }
        }
        for (String value : safeList(rules.mustNotContain)) {
            if (answer.contains(value)) {
                failures.add(failure(spec, "FINAL_ANSWER", "包含禁止内容：" + value, value, answer));
            }
        }
        for (String check : safeList(rules.requiredSemanticChecks)) {
            String issue = semanticIssue(check, answer, evidence);
            if (!issue.isBlank()) {
                failures.add(failure(spec, "FINAL_ANSWER", issue, check, answer));
            }
        }
        return failures;
    }

    private static void checkMustUseAnyTool(List<AiCaseFailure> failures,
                                            AiCaseSpec spec,
                                            ChatPlan plan,
                                            List<String> expectedTools) {
        if (expectedTools == null || expectedTools.isEmpty()) {
            return;
        }
        List<String> actualTools = plan.toolPlan().stream().map(ToolStep::toolName).toList();
        boolean matched = expectedTools.stream().anyMatch(actualTools::contains);
        if (!matched) {
            failures.add(failure(spec, "PLANNER", "toolPlan did not use any expected tool",
                expectedTools, actualTools));
        }
    }

    private static void checkMustNotUseTools(List<AiCaseFailure> failures,
                                             AiCaseSpec spec,
                                             ChatPlan plan,
                                             List<String> forbiddenTools) {
        if (forbiddenTools == null || forbiddenTools.isEmpty()) {
            return;
        }
        List<String> actualTools = plan.toolPlan().stream().map(ToolStep::toolName).toList();
        for (String tool : forbiddenTools) {
            if (actualTools.contains(tool)) {
                failures.add(failure(spec, "PLANNER", "toolPlan used forbidden tool: " + tool,
                    forbiddenTools, actualTools));
            }
        }
    }

    private static void checkRequirements(List<AiCaseFailure> failures,
                                          AiCaseSpec spec,
                                          AnswerRequirement actual,
                                          AiCaseSpec.ExpectedRequirements expected) {
        if (expected == null) {
            return;
        }
        checkEquals(failures, spec, "PLANNER", "requiresConcreteExample",
            expected.requiresConcreteExample, actual.requiresConcreteExample());
        checkEquals(failures, spec, "PLANNER", "requiresSpecificEntity",
            expected.requiresSpecificEntity, actual.requiresSpecificEntity());
        checkEquals(failures, spec, "PLANNER", "requiresStorytelling",
            expected.requiresStorytelling, actual.requiresStorytelling());
        checkEquals(failures, spec, "PLANNER", "requiresDetailedProcess",
            expected.requiresDetailedProcess, actual.requiresDetailedProcess());
        checkEquals(failures, spec, "PLANNER", "avoidConceptualOpening",
            expected.avoidConceptualOpening, actual.avoidConceptualOpening());
        checkEquals(failures, spec, "PLANNER", "avoidRepeatingPreviousExplanation",
            expected.avoidRepeatingPreviousExplanation, actual.avoidRepeatingPreviousExplanation());
        checkEquals(failures, spec, "PLANNER", "allowModelKnowledge",
            expected.allowModelKnowledge, actual.allowModelKnowledge());
        checkEquals(failures, spec, "PLANNER", "mustDistinguishTextEvidenceAndSupplement",
            expected.mustDistinguishTextEvidenceAndSupplement,
            actual.mustDistinguishTextEvidenceAndSupplement());
        checkEquals(failures, spec, "PLANNER", "avoidRepeatingSourcePhrases",
            expected.avoidRepeatingSourcePhrases, actual.avoidRepeatingSourcePhrases());
    }

    private static String semanticIssue(String check, String answer, CollectedEvidence evidence) {
        String text = normalize(answer);
        if (check.contains("不能只复述原文")) {
            String evidenceText = normalize(evidence == null ? "" : evidence.formattedEvidence());
            if (text.length() < 80 || (!evidenceText.isBlank() && evidenceText.contains(text))) {
                return "回答可能只是复述原文，没有形成解释或叙事。";
            }
        }
        if (check.contains("必须讲出故事过程")
            && !text.matches(".*(从前|后来|此刻|先|再|于是|结果|过程|变化).*")) {
            return "回答缺少故事过程链条。";
        }
        if (check.contains("必须说明没有具体姓名")
            && !text.matches(".*(没有|未给出|缺少).*?(具体姓名|姓名|村庄|日期).*")) {
            return "回答没有说明资料缺少具体姓名、村庄或日期。";
        }
        if (check.contains("回答不能大段重复上一轮概念解释")
            && text.matches(".*(概念上|抽象地说|本质上).*")) {
            return "回答仍像上一轮概念解释。";
        }
        if (check.contains("应直接进入例子或说明证据不足")
            && !text.matches(".*(例子|场景|当前资料|证据不足|缺少).*")) {
            return "回答没有直接进入例子，也没有说明证据不足。";
        }
        if (check.contains("应说明当前资料没有给出具体姓名")
            && !text.matches(".*(当前资料|原文|材料).*?(没有|未给出|缺少).*?(具体姓名|姓名).*")) {
            return "回答没有说明当前资料未给出具体姓名。";
        }
        if (check.contains("可以解释这是一类人")
            && !text.matches(".*(一类人|一类现场|群体|这类人).*")) {
            return "回答没有说明这是原文记录的一类人或一类现场。";
        }
        return "";
    }

    private static void checkEquals(List<AiCaseFailure> failures,
                                    AiCaseSpec spec,
                                    String stage,
                                    String field,
                                    Object expected,
                                    Object actual) {
        if (expected != null && !Objects.equals(expected, actual)) {
            failures.add(failure(spec, stage, field + " mismatch", expected, actual));
        }
    }

    private static void checkAnyOf(List<AiCaseFailure> failures,
                                   AiCaseSpec spec,
                                   String stage,
                                   String field,
                                   List<String> expectedAnyOf,
                                   String expectedSingle,
                                   String actual) {
        if (expectedAnyOf != null && !expectedAnyOf.isEmpty()) {
            if (!expectedAnyOf.contains(actual)) {
                failures.add(failure(spec, stage, field + " not in allowed values", expectedAnyOf, actual));
            }
            return;
        }
        checkEquals(failures, spec, stage, field, expectedSingle, actual);
    }

    private static AiCaseFailure failure(AiCaseSpec spec, String stage, String reason, Object expected, Object actual) {
        return new AiCaseFailure(spec.caseId, spec.caseName, stage, reason, expected, actual);
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    record AiCaseFailure(String caseId,
                         String caseName,
                         String stage,
                         String reason,
                         Object expected,
                         Object actual) {
        String reportText() {
            return """
                [AI_CASE_FAILED]
                caseId=%s
                caseName=%s
                stage=%s
                reason=%s
                expected=%s
                actual=%s
                """.formatted(caseId, caseName, stage, reason, expected, actual);
        }
    }
}
