package com.example.httpreading.evolution;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.example.httpreading.service.ModelClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CandidatePromptGenerator {
    private static final Logger log = LoggerFactory.getLogger(CandidatePromptGenerator.class);
    private static final Pattern INTERNAL_IDENTIFIER =
        Pattern.compile("\\b[a-z][a-z0-9]*_[a-z0-9_]+\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern OVERBROAD_HYPOTHETICAL_LABEL =
        Pattern.compile("(?s)(?:所有|每个|任何).{0,16}(?:具体|数字|细节|例子).{0,24}"
            + "(?:逐项|分别|都必须).{0,16}(?:假设|标注)");

    private final ModelClient modelClient;
    private final ObjectMapper objectMapper;

    public CandidatePromptGenerator(ModelClient modelClient, ObjectMapper objectMapper) {
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
    }

    public PromptOverride generate(List<EvolutionCaseResult> baselineResults) {
        Map<FailureType, Integer> failures = failureCounts(baselineResults);
        if (failures.isEmpty()) {
            return PromptOverride.none();
        }
        try {
            String raw = modelClient.chat(prompt(failures, failedSamples(baselineResults)));
            JsonNode json = objectMapper.readTree(extractJson(raw));
            if (!json.path("plannerPatch").asText("").isBlank()) {
                throw new IllegalArgumentException("candidate output contains a Planner patch");
            }
            PromptOverride generated = PromptOverride.finalAnswerOnly(
                json.path("finalAnswerPatch").asText(""));
            if (!generated.isEmpty()) {
                validateGenerated(generated);
                return generated;
            }
        } catch (Exception exception) {
            log.warn("Self-Evolution 候选 Prompt 生成失败，使用确定性 patch: {}", exception.getMessage());
        }
        return deterministicPatch(failures);
    }

    private String prompt(Map<FailureType, Integer> failures,
                          List<Map<String, Object>> failedSamples) throws Exception {
        return """
            你是一个通用 AI 回答 Prompt 的改进器。根据评测失败生成一个最小的行为策略 patch。
            该 patch 只会被追加到 FinalAnswer Prompt 的“可进化策略区”。

            约束：
            - 不生成或讨论 Planner，不引用任何项目内部字段、Java 类型、工具名或业务名。
            - 不重写角色介绍、输入格式、输出格式、事实边界、安全规则等固定契约。
            - 不使用“忽略之前指令”“覆盖固定规则”等越权措辞。
            - 只描述跨领域可复用的回答行为，最多 8 条，失败未涉及的行为不要修改。
            - 必须区分“历史/事实内容补写”和“解释理论的教学举例”：
              历史或真实事件的想象补写，应在整个场景开始前统一说明“可能、假设或用于理解”，
              一次说明覆盖连续场景，不要求每个细节重复标注，也不能冒充原文事实；
              教学举例中的虚构人物、数字和生活场景不承担真实事件声明，无需真实性标签。
            - 禁止生成“所有具体例子、每个数字或每个新增细节都必须逐项标注假设”的过度规则。
            - 只输出 JSON：{"finalAnswerPatch":"可为空"}

            failureCounts:
            %s
            failedSamples:
            %s
            """.formatted(
            objectMapper.writeValueAsString(failures),
            objectMapper.writeValueAsString(failedSamples));
    }

    private void validateGenerated(PromptOverride generated) {
        String patch = generated.finalAnswerPatch();
        String normalized = patch.toLowerCase();
        if (!generated.plannerPatch().isBlank()
            || normalized.contains("planner")
            || normalized.contains("answerrequirement")
            || patch.contains("忽略之前")
            || patch.contains("覆盖固定")
            || patch.contains("修改固定")
            || patch.contains("修改输出格式")
            || OVERBROAD_HYPOTHETICAL_LABEL.matcher(patch).find()
            || INTERNAL_IDENTIFIER.matcher(patch).find()) {
            throw new IllegalArgumentException("candidate patch attempts to modify a fixed or project-specific contract");
        }
    }

    private Map<FailureType, Integer> failureCounts(List<EvolutionCaseResult> results) {
        Map<FailureType, Integer> counts = new EnumMap<>(FailureType.class);
        for (EvolutionCaseResult result : results == null ? List.<EvolutionCaseResult>of() : results) {
            if (result.passed()) continue;
            for (FailureType type : result.failureTypes()) {
                if (type != FailureType.EMPTY_OR_MODEL_ERROR
                    && type != FailureType.EVALUATION_ERROR) {
                    counts.merge(type, 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    private List<Map<String, Object>> failedSamples(List<EvolutionCaseResult> results) {
        List<Map<String, Object>> samples = new java.util.ArrayList<>();
        for (EvolutionCaseResult result : results == null ? List.<EvolutionCaseResult>of() : results) {
            if (result.passed()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("caseId", result.caseId());
            item.put("failureTypes", result.failureTypes());
            item.put("judgeReasons", result.reasons());
            samples.add(item);
            if (samples.size() == 10) break;
        }
        return List.copyOf(samples);
    }

    private PromptOverride deterministicPatch(Map<FailureType, Integer> failures) {
        List<String> finalRules = new java.util.ArrayList<>();
        if (failures.containsKey(FailureType.TOO_CONCEPTUAL)) {
            finalRules.add("用户要求直接解释或举例时，第一段直接进入场景或结论，不要先下定义。");
        }
        if (failures.containsKey(FailureType.TOO_SIMPLE)) {
            finalRules.add("回答原因类问题时给出结论、因果链和回扣，不能只给一句判断。");
        }
        if (failures.containsKey(FailureType.MISSING_EXAMPLE)) {
            finalRules.add("需要例子时，给出可辨识的主体、行动、场景与结果，并说明例子如何对应结论。");
        }
        if (failures.containsKey(FailureType.MISSING_STORY_DETAIL)) {
            finalRules.add("需要完整叙事时，按起点、发展、转折、结果组织内容并回扣观点。");
        }
        if (failures.containsKey(FailureType.REPETITIVE)) {
            finalRules.add("连续对话中只回答本轮新增点，不能复述已有结论。");
        }
        if (failures.containsKey(FailureType.NOT_DIRECT) || failures.containsKey(FailureType.OFF_TOPIC)) {
            finalRules.add("首段先给出问题的核心结论，后续内容必须围绕该问题展开。");
        }
        if (failures.containsKey(FailureType.EVIDENCE_BOUNDARY)) {
            finalRules.add("历史或事实语境中的想象补写，在整个连续场景开始前统一说明“可能、假设或用于理解”，"
                + "并且不能归因成原文事实；用于解释理论的教学例子无需声明真实性，也不要求逐项标注。");
        }
        return PromptOverride.finalAnswerOnly(String.join("\n", finalRules));
    }

    private String extractJson(String raw) {
        String value = raw == null ? "" : raw.trim();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("candidate prompt output is not JSON");
        }
        return value.substring(start, end + 1);
    }
}
