package com.example.httpreading.evolution;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final Pattern ALLOWED_EVIDENCE_MODE = Pattern.compile(
        "STRICT_SOURCE|SOURCE_GROUNDED_NARRATIVE|PEDAGOGICAL_ILLUSTRATION");

    private final ModelClient modelClient;
    private final ObjectMapper objectMapper;

    public CandidatePromptGenerator(ModelClient modelClient, ObjectMapper objectMapper) {
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
    }

    public PromptOverride generate(List<EvolutionCaseResult> baselineResults) {
        return generate(new CandidateGenerationContext(
            List.of(), baselineResults, List.of()));
    }

    public PromptOverride generate(CandidateGenerationContext context) {
        List<EvolutionCaseResult> activeResults = activeResults(context);
        Map<FailureType, Integer> failures = failureCounts(
            context.baselineResults(), activeResults);
        if (failures.isEmpty()) {
            return PromptOverride.none();
        }
        Set<EvidenceIssueType> evidenceIssueTypes = evidenceIssueTypes(
            context.baselineResults(), activeResults);
        try {
            String raw = modelClient.chat(prompt(
                failures, summarizeContext(context), context));
            JsonNode json = objectMapper.readTree(extractJson(raw));
            if (!json.path("plannerPatch").asText("").isBlank()) {
                throw new IllegalArgumentException("candidate output contains a Planner patch");
            }
            PromptOverride generated = PromptOverride.finalAnswerOnly(
                json.path("finalAnswerPatch").asText(""));
            if (!generated.isEmpty()) {
                generated = ensureEvidenceBoundaryRules(generated, evidenceIssueTypes);
                validateGenerated(generated);
                return generated;
            }
        } catch (Exception exception) {
            log.warn("Self-Evolution 候选 Prompt 生成失败，使用确定性 patch: {}", exception.getMessage());
        }
        return deterministicPatch(failures, evidenceIssueTypes);
    }

    private String prompt(Map<FailureType, Integer> failures,
                          Map<String, Object> failureSummary,
                          CandidateGenerationContext context) throws Exception {
        return """
            你是一个通用 AI 回答 Prompt 的改进器。根据 baseline 和历次 candidate 的对比，生成
            一个最小的完整行为策略 patch。该 patch 会替换上一轮候选策略，并追加到 FinalAnswer
            Prompt 的“可进化策略区”；不要在上一轮 patch 后继续追加。

            约束：
            - 不生成或讨论 Planner，不引用任何项目内部字段、Java 类型、工具名或业务名。
            - 不重写角色介绍、输入格式、输出格式、事实边界、安全规则等固定契约。
            - 不使用“忽略之前指令”“覆盖固定规则”等越权措辞。
            - 只描述跨领域可复用的回答行为，最多 8 条，失败未涉及的行为不要修改。
            - 同时修复原始失败和上一轮持续失败，消除上一轮新增回归，并保持已经修复的用例。
            - 不要机械复制 previousPatch；应删除已证明过度、冲突或导致回归的规则，输出完整替代版本。
            - 必须区分“历史/事实内容补写”和“解释理论的教学举例”：
              历史或真实事件的想象补写，应在整个场景开始前统一说明“可能、假设或用于理解”，
              一次说明覆盖连续场景，不要求每个细节重复标注，也不能冒充原文事实；
              也可明确声明“以下为助手自主构造、没有资料依据、仅用于理解”；
              教学举例中的虚构人物、数字和生活场景不承担真实事件声明，无需真实性标签。
            - SOURCE_GROUNDED_NARRATIVE 只有在证据为 PARTIAL/MISSING、任务允许补写且回答实际使用
              证据外叙事时，才要求先声明“以下为助手自主构造、没有资料依据，仅用于理解”。
              EvidenceCompleteness=COMPLETE 或问题明确“只根据证据”时，必须直接组织现有证据，
              不添加无依据声明，不另编故事。
            - memory 只能表述为未核验的“历史记忆摘要/之前记录”。即使摘要中出现书名，也只能说
              “历史记忆摘要提到《某书》相关内容，当前未提供原文，无法核验”，不能把书名列为
              “关键来源”，也不能提升为原文、RAG 或书籍事实来源。
            - 正文和回扣同样受上述限制。只能说“这个自主构造的故事演示了历史记忆摘要中的态度
              变化”，不能说“这个故事反映/印证了《某书》中记载的历史动态”。结尾免责声明不能
              抵消正文中的错误来源归因。
            - STRICT_SOURCE 下不得增加对象世界中的事实；可以说明证据缺口、证据冲突、撤回无来源
              结论和还需要哪类资料，这些是证据边界说明，不是证据外事实。
            - 禁止生成“所有具体例子、每个数字或每个新增细节都必须逐项标注假设”的过度规则。
            - 只输出 JSON：{"finalAnswerPatch":"可为空"}

            failureCounts:
            %s
            aggregatedFailureSummary:
            %s
            previousPatch:
            %s
            iteration:
            %d
            """.formatted(
            objectMapper.writeValueAsString(failures),
            objectMapper.writeValueAsString(failureSummary),
            objectMapper.writeValueAsString(previousPatch(context)),
            context.nextIteration());
    }

    private void validateGenerated(PromptOverride generated) {
        String patch = generated.finalAnswerPatch();
        String normalized = patch.toLowerCase();
        String projectIdentifierCheck =
            ALLOWED_EVIDENCE_MODE.matcher(patch).replaceAll("");
        if (!generated.plannerPatch().isBlank()
            || normalized.contains("planner")
            || normalized.contains("answerrequirement")
            || patch.contains("忽略之前")
            || patch.contains("覆盖固定")
            || patch.contains("修改固定")
            || patch.contains("修改输出格式")
            || patch.length() > 4000
            || isOverbroadSourceConstructionRule(patch)
            || OVERBROAD_HYPOTHETICAL_LABEL.matcher(patch).find()
            || INTERNAL_IDENTIFIER.matcher(projectIdentifierCheck).find()) {
            throw new IllegalArgumentException("candidate patch attempts to modify a fixed or project-specific contract");
        }
    }

    private boolean isOverbroadSourceConstructionRule(String patch) {
        boolean requiresSourceDeclaration = patch.contains("SOURCE_GROUNDED_NARRATIVE")
            && patch.matches("(?s).*(?:必须|一律).{0,80}(?:声明|自主构造).*");
        boolean conditional = patch.contains("如需证据外补写")
            || patch.matches("(?s).*(?:PARTIAL|MISSING|证据不完整|证据缺少).{0,80}(?:补写|构造).*");
        return requiresSourceDeclaration && !conditional;
    }

    private PromptOverride ensureEvidenceBoundaryRules(
        PromptOverride generated,
        Set<EvidenceIssueType> issueTypes) {
        if (issueTypes.isEmpty()) {
            return generated;
        }
        String patch = generated.finalAnswerPatch();
        List<String> requiredRules = new java.util.ArrayList<>();
        if (issueTypes.contains(EvidenceIssueType.UNLABELED_UNGROUNDED_SECTION)
            && (!patch.contains("PARTIAL/MISSING")
                || !patch.contains("以下为助手自主构造、没有资料依据，仅用于理解"))) {
            requiredRules.add("SOURCE_GROUNDED_NARRATIVE 仅在证据为 PARTIAL/MISSING、"
                + "任务允许且实际需要证据外补写时，先用“以下为助手自主构造、没有资料依据，"
                + "仅用于理解”统一声明再进入构造内容；证据 COMPLETE 或用户要求只根据证据时，"
                + "直接组织已有证据，不声明无依据，也不另编故事。");
        }
        if (issueTypes.contains(EvidenceIssueType.FALSE_SOURCE_ATTRIBUTION)
            && (!patch.contains("当前未提供原文，无法核验")
                || !patch.contains("不能把书名列为“关键来源”"))) {
            requiredRules.add("历史记忆摘要只能作为未核验的 memory/之前记录。"
                + "摘要出现书名时，应表述为“历史记忆摘要提到《某书》相关内容，"
                + "当前未提供原文，无法核验”；不能把书名列为“关键来源”，"
                + "也不能把摘要提升为原文、RAG 或书籍事实。");
        }
        if (issueTypes.contains(EvidenceIssueType.FALSE_SOURCE_ATTRIBUTION)
            && (!patch.contains("正文和回扣")
                || !patch.contains("不能说“这个故事反映/印证了《某书》中记载的历史动态”"))) {
            requiredRules.add("正文和回扣同样不得把自主构造的故事归因给书籍。"
                + "可以说“这个自主构造的故事演示了历史记忆摘要中的态度变化”，"
                + "不能说“这个故事反映/印证了《某书》中记载的历史动态”；"
                + "结尾的未核验声明不能抵消正文中的错误来源归因。");
        }
        if (issueTypes.contains(EvidenceIssueType.STRICT_SOURCE_UNSUPPORTED_CONTENT)
            && !patch.contains("证据边界说明")) {
            requiredRules.add("STRICT_SOURCE 不得增加证据外的人物、数字、词义、事件或因果事实；"
                + "但可以作证据边界说明，包括指出缺失或冲突、撤回无来源结论，以及说明还需要"
                + "哪类资料。");
        }
        if (issueTypes.contains(EvidenceIssueType.UNSUPPORTED_FACTUAL_CLAIM)
            && !patch.contains("未经证据支持的具体比例")) {
            requiredRules.add("解释理论时不得把未经证据支持的具体比例、研究结论或来源写成事实；"
                + "教学场景可以构造，但不能承担外部事实声明。");
        }
        if (requiredRules.isEmpty()) {
            return generated;
        }
        String requiredRule = String.join("\n", requiredRules);
        return PromptOverride.finalAnswerOnly(
            patch.isBlank() ? requiredRule : patch + "\n" + requiredRule);
    }

    private Map<FailureType, Integer> failureCounts(
        List<EvolutionCaseResult> baseline,
        List<EvolutionCaseResult> active) {
        Map<FailureType, Integer> counts = new EnumMap<>(FailureType.class);
        java.util.stream.Stream.concat(
            baseline == null ? java.util.stream.Stream.empty() : baseline.stream(),
            active == null ? java.util.stream.Stream.empty() : active.stream())
            .filter(result -> !result.passed())
            .forEach(result -> {
                for (FailureType type : result.failureTypes()) {
                    if (type != FailureType.EMPTY_OR_MODEL_ERROR
                        && type != FailureType.EVALUATION_ERROR) {
                        counts.merge(type, 1, Integer::sum);
                    }
                }
            });
        return counts;
    }

    private List<EvolutionCaseResult> activeResults(CandidateGenerationContext context) {
        EvolutionIterationResult previous = context.previousIteration();
        return previous == null ? List.of() : previous.results();
    }

    private Set<EvidenceIssueType> evidenceIssueTypes(
        List<EvolutionCaseResult> baseline,
        List<EvolutionCaseResult> active) {
        Set<EvidenceIssueType> result = EnumSet.noneOf(EvidenceIssueType.class);
        java.util.stream.Stream.concat(
            baseline == null ? java.util.stream.Stream.empty() : baseline.stream(),
            active == null ? java.util.stream.Stream.empty() : active.stream())
            .filter(value -> !value.passed())
            .flatMap(value -> value.evidenceIssues().stream())
            .map(EvidenceIssue::type)
            .forEach(result::add);
        return Set.copyOf(result);
    }

    private String previousPatch(CandidateGenerationContext context) {
        EvolutionIterationResult previous = context.previousIteration();
        return previous == null ? "" : previous.prompt().finalAnswerPatch();
    }

    Map<String, Object> summarizeContext(CandidateGenerationContext context) {
        Map<String, EvolutionEvalCase> casesById = context.cases().stream()
            .collect(java.util.stream.Collectors.toMap(
                EvolutionEvalCase::id,
                value -> value,
                (first, ignored) -> first,
                LinkedHashMap::new));
        Map<String, EvolutionCaseResult> baselineById = byCaseId(context.baselineResults());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("originalFailures", context.baselineResults().stream()
            .filter(result -> !result.passed())
            .map(result -> caseSummary(casesById.get(result.caseId()), result))
            .toList());
        summary.put("baselinePassingGuards", context.baselineResults().stream()
            .filter(EvolutionCaseResult::passed)
            .map(result -> guardSummary(casesById.get(result.caseId())))
            .toList());

        EvolutionIterationResult previous = context.previousIteration();
        if (previous == null) {
            summary.put("previousComparison", Map.of());
        } else {
            List<Map<String, Object>> fixed = new java.util.ArrayList<>();
            List<Map<String, Object>> persistent = new java.util.ArrayList<>();
            List<Map<String, Object>> regressions = new java.util.ArrayList<>();
            for (EvolutionCaseResult candidate : previous.results()) {
                EvolutionCaseResult baseline = baselineById.get(candidate.caseId());
                if (baseline == null) continue;
                Map<String, Object> item =
                    caseSummary(casesById.get(candidate.caseId()), candidate);
                if (!baseline.passed() && candidate.passed()) {
                    fixed.add(item);
                } else if (!baseline.passed() && !candidate.passed()) {
                    persistent.add(item);
                } else if (baseline.passed() && !candidate.passed()) {
                    regressions.add(item);
                }
            }
            Map<String, Object> comparison = new LinkedHashMap<>();
            comparison.put("fixedMustPreserve", fixed);
            comparison.put("persistentFailures", persistent);
            comparison.put("newRegressionsMustRemove", regressions);
            summary.put("previousComparison", comparison);
        }
        summary.put("iterationHistory", context.priorIterations().stream().map(iteration -> Map.of(
            "iteration", iteration.iteration(),
            "averageScore", iteration.aggregate().averageScore(),
            "passRate", iteration.aggregate().passRate(),
            "hardFailures", iteration.aggregate().hardFailures(),
            "safetyPassed", iteration.safetyPassed(),
            "fixedCaseIds", iteration.fixedCaseIds(),
            "persistentFailureCaseIds", iteration.persistentFailureCaseIds(),
            "regressionCaseIds", iteration.regressionCaseIds())).toList());
        return Map.copyOf(summary);
    }

    private Map<String, EvolutionCaseResult> byCaseId(List<EvolutionCaseResult> results) {
        return (results == null ? List.<EvolutionCaseResult>of() : results).stream()
            .collect(java.util.stream.Collectors.toMap(
                EvolutionCaseResult::caseId,
                value -> value,
                (first, ignored) -> first,
                LinkedHashMap::new));
    }

    private Map<String, Object> guardSummary(EvolutionEvalCase evalCase) {
        if (evalCase == null) return Map.of();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("caseId", evalCase.id());
        item.put("boundary", evalCase.boundarySpec().boundary());
        item.put("evidenceUseMode",
            evalCase.expectedBehavior().evidencePolicy().evidenceUseMode());
        item.put("evidenceCompleteness", evalCase.boundarySpec().evidenceCompleteness());
        item.put("question", evalCase.request().getQuestion());
        item.put("requiredBehaviors", evalCase.expectedBehavior().scoringCriteria().stream()
            .map(EvolutionEvalCase.ScoringCriterion::description).toList());
        return Map.copyOf(item);
    }

    private Map<String, Object> caseSummary(EvolutionEvalCase evalCase,
                                            EvolutionCaseResult result) {
        Map<String, Object> item = new LinkedHashMap<>(guardSummary(evalCase));
        item.put("passed", result.passed());
        item.put("score", result.score());
        item.put("failureTypes", result.failureTypes());
        item.put("reasons", result.reasons().stream().limit(3).toList());
        item.put("evidenceIssues", result.evidenceIssues().stream().map(issue -> Map.of(
            "type", issue.type(),
            "summary", issue.summary(),
            "correction", issue.correction(),
            "examples", issue.examples())).toList());
        return Map.copyOf(item);
    }

    Map<String, Object> summarizeFailures(List<EvolutionCaseResult> results) {
        List<EvolutionCaseResult> failed = (results == null
            ? List.<EvolutionCaseResult>of()
            : results).stream().filter(result -> !result.passed()).toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalFailedCases", failed.size());

        List<Map<String, Object>> content = new java.util.ArrayList<>();
        for (FailureType type : FailureType.values()) {
            List<EvolutionCaseResult> matching = failed.stream()
                .filter(result -> result.failureTypes().contains(type))
                .toList();
            if (matching.isEmpty()
                || type == FailureType.EVIDENCE_BOUNDARY
                || type == FailureType.EVALUATION_ERROR
                || type == FailureType.EMPTY_OR_MODEL_ERROR) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("failureType", type);
            item.put("count", matching.size());
            item.put("representativeReasons", matching.stream()
                .flatMap(result -> result.reasons().stream())
                .filter(reason -> !reason.startsWith("证据边界："))
                .distinct().limit(2).toList());
            content.add(item);
        }
        summary.put("contentFailures", content);

        List<Map<String, Object>> evidence = new java.util.ArrayList<>();
        for (EvidenceIssueType type : EvidenceIssueType.values()) {
            List<EvidenceIssue> matching = failed.stream()
                .flatMap(result -> result.evidenceIssues().stream())
                .filter(issue -> issue.type() == type)
                .toList();
            if (matching.isEmpty()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("issueType", type);
            item.put("count", matching.stream().mapToInt(EvidenceIssue::count).sum());
            item.put("summary", matching.get(0).summary());
            item.put("correction", matching.get(0).correction());
            item.put("representativeExamples", matching.stream()
                .flatMap(issue -> issue.examples().stream())
                .distinct().limit(2).toList());
            evidence.add(item);
        }
        summary.put("evidenceFailures", evidence);
        return Map.copyOf(summary);
    }

    private PromptOverride deterministicPatch(
        Map<FailureType, Integer> failures,
        Set<EvidenceIssueType> issueTypes) {
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
        if (issueTypes.contains(EvidenceIssueType.UNLABELED_UNGROUNDED_SECTION)) {
            finalRules.add("SOURCE_GROUNDED_NARRATIVE 仅在证据为 PARTIAL/MISSING 且实际需要"
                + "证据外补写时，先声明内容由助手构造；证据 COMPLETE 或用户要求只根据证据时，"
                + "直接组织已有证据，不声明无依据，不另编故事。");
        }
        if (issueTypes.contains(EvidenceIssueType.FALSE_SOURCE_ATTRIBUTION)) {
            finalRules.add("历史记忆摘要只能作为未核验的 memory/之前记录；摘要出现书名时写成"
                + "“历史记忆摘要提到《某书》相关内容，当前未提供原文，无法核验”，不能把书名"
                + "列为“关键来源”或提升为原文、RAG、书籍事实。正文和回扣也不得把构造内容归因"
                + "给书籍，只能说构造故事演示了 memory 摘要中的变化。");
        }
        if (issueTypes.contains(EvidenceIssueType.STRICT_SOURCE_UNSUPPORTED_CONTENT)) {
            finalRules.add("STRICT_SOURCE 不增加证据外的人物、数字、词义、事件或因果事实；可以说明"
                + "证据缺口、冲突、撤回无来源结论，以及还需要哪类资料。");
        }
        if (issueTypes.contains(EvidenceIssueType.UNSUPPORTED_FACTUAL_CLAIM)) {
            finalRules.add("不得把证据没有提供的具体比例、研究结论或来源写成事实。");
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
