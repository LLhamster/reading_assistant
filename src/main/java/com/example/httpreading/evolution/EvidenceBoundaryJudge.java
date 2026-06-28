package com.example.httpreading.evolution;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.example.httpreading.service.ModelClient;
import com.example.httpreading.service.ModelClientException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * Independent evidence-only judge. It deliberately receives no baseline/candidate
 * label and does not score answer quality.
 */
@Service
public class EvidenceBoundaryJudge {
    private static final int MAX_PARSE_ATTEMPTS = 2;
    private static final Set<String> CLASSIFICATIONS = Set.of(
        "SUPPORTED", "GENERAL_KNOWLEDGE", "LABELED_HYPOTHETICAL", "UNSUPPORTED_CONCRETE");
    private static final Set<String> LABEL_POSITIONS = Set.of(
        "BEFORE_OR_AT_FIRST", "AFTER", "MISSING", "NOT_APPLICABLE");
    private static final Pattern UNSUPPORTED_SOURCE_ATTRIBUTION = Pattern.compile(
        "(?s)(?:关键来源.{0,120}《[^》]+》|根据《[^》]+》|《[^》]+》中(?:记载|指出|描述)|"
            + "(?:原文|书中|报告中)(?:记载|指出|描述))");
    private static final Pattern HYPOTHETICAL_LABEL = Pattern.compile(
        "(?:假设|虚构|模拟场景|帮助理解(?:的|型)?(?:故事|案例|情节)|以下(?:故事|案例|情节).{0,12}(?:假设|虚构))");

    private final ModelClient modelClient;
    private final ObjectMapper objectMapper;

    public EvidenceBoundaryJudge(ModelClient modelClient, ObjectMapper objectMapper) {
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
    }

    public EvidenceReview review(EvolutionEvalCase evalCase, String answer) {
        List<String> deterministic = deterministicViolations(evalCase, answer);
        String lastError = "";
        for (int attempt = 1; attempt <= MAX_PARSE_ATTEMPTS; attempt++) {
            try {
                EvidenceReview modelReview = parse(
                    modelClient.chat(prompt(evalCase, answer)),
                    evalCase.expectedBehavior().evidencePolicy(),
                    answer);
                List<String> violations = new ArrayList<>(deterministic);
                violations.addAll(modelReview.violations());
                return new EvidenceReview(
                    true,
                    violations.stream().filter(value -> !value.isBlank()).distinct().toList(),
                    modelReview.claims(),
                    "");
            } catch (ModelClientException exception) {
                return EvidenceReview.error("evidence judge model failed: " + exception.getMessage());
            } catch (Exception exception) {
                lastError = exception.getMessage();
            }
        }
        return EvidenceReview.error("evidence judge parse failed: " + lastError);
    }

    private List<String> deterministicViolations(EvolutionEvalCase evalCase, String answer) {
        if (hasBookEvidence(evalCase) || answer == null || answer.isBlank()) {
            return List.of();
        }
        if (UNSUPPORTED_SOURCE_ATTRIBUTION.matcher(answer).find()) {
            return List.of("当前用例没有 current_page/RAG 书籍证据，回答却把具体书籍、原文或报告列为事实来源。");
        }
        return List.of();
    }

    private boolean hasBookEvidence(EvolutionEvalCase evalCase) {
        return evalCase.collectedEvidence().stream().anyMatch(value ->
            "current_page".equals(value.type()) || value.type().startsWith("rag"));
    }

    private String prompt(EvolutionEvalCase evalCase, String answer) throws Exception {
        return """
            你是独立的 Evidence Boundary Judge，只检查证据安全，不评价表达质量，也不知道回答来自
            baseline 还是 candidate。

            对回答中的每个具体人物、地点、时间、数字、引语、事件过程和来源归因进行分类：
            - SUPPORTED：由 collectedEvidence 直接支持。
            - GENERAL_KNOWLEDGE：允许的一般知识解释，不被表述为原文或本题真实事实。
            - LABELED_HYPOTHETICAL：假设内容，并且在该内容首次出现之前或同一句已明确标注假设/虚构/帮助理解。
            - UNSUPPORTED_CONCRETE：证据不支持，却被写成真实事实、原文内容或真实来源。

            memory 和 recent_dialogue 只是历史上下文，不是书籍原文或 RAG 证据。
            结尾才补充“这是辅助案例”，不能补救前文已经按事实口吻叙述的虚构内容。
            如果 evidencePolicy 不允许一般知识或假设，也必须记录违规。

            只输出 JSON：
            {
              "claims":[
                {"claim":"具体声明","classification":"SUPPORTED|GENERAL_KNOWLEDGE|LABELED_HYPOTHETICAL|UNSUPPORTED_CONCRETE","reason":"证据判断"}
              ],
              "hypothetical_content_present":true,
              "hypothetical_label_position":"BEFORE_OR_AT_FIRST|AFTER|MISSING|NOT_APPLICABLE",
              "violations":["具体违规及缺少的证据"]
            }

            输入：
            %s
            """.formatted(objectMapper.writeValueAsString(java.util.Map.of(
            "question", evalCase.request().getQuestion(),
            "collectedEvidence", evalCase.collectedEvidence(),
            "evidencePolicy", evalCase.expectedBehavior().evidencePolicy(),
            "answer", answer == null ? "" : answer)));
    }

    private EvidenceReview parse(String raw,
                                 EvolutionEvalCase.EvidencePolicy policy,
                                 String answer) throws Exception {
        JsonNode root = parseObject(raw);
        List<ClaimReview> claims = new ArrayList<>();
        root.path("claims").forEach(node -> {
            String classification = node.path("classification").asText();
            if (!CLASSIFICATIONS.contains(classification)) {
                throw new IllegalArgumentException("unknown evidence classification: " + classification);
            }
            String claim = node.path("claim").asText("").trim();
            if (claim.isBlank()) {
                throw new IllegalArgumentException("evidence claim must not be blank");
            }
            claims.add(new ClaimReview(claim, classification, node.path("reason").asText()));
        });
        if (claims.isEmpty()) {
            throw new IllegalArgumentException("evidence judge must classify at least one claim");
        }

        List<String> violations = new ArrayList<>();
        root.path("violations").forEach(node -> {
            String value = node.asText("").trim();
            if (!value.isBlank()) violations.add(value);
        });
        claims.stream()
            .filter(claim -> "UNSUPPORTED_CONCRETE".equals(claim.classification()))
            .forEach(claim -> violations.add(
                "未支持的具体内容：“" + claim.claim() + "”；" + claim.reason()));
        if (!policy.allowGeneralExplanation()) {
            claims.stream()
                .filter(claim -> "GENERAL_KNOWLEDGE".equals(claim.classification()))
                .forEach(claim -> violations.add(
                    "本用例不允许一般知识补充：“" + claim.claim() + "”。"));
        }

        if (!root.has("hypothetical_content_present") || !root.has("hypothetical_label_position")) {
            throw new IllegalArgumentException("evidence judge must report hypothetical label status");
        }
        boolean hypotheticalPresent = root.path("hypothetical_content_present").asBoolean(false)
            || claims.stream().anyMatch(claim ->
                "LABELED_HYPOTHETICAL".equals(claim.classification()));
        String labelPosition = root.path("hypothetical_label_position").asText("NOT_APPLICABLE");
        if (!LABEL_POSITIONS.contains(labelPosition)) {
            throw new IllegalArgumentException("unknown hypothetical label position: " + labelPosition);
        }
        if (hypotheticalPresent) {
            if (!policy.allowHypotheticalExample()) {
                violations.add("本用例不允许假设性内容。");
            } else if (policy.mustLabelHypotheticalExample()
                && (!"BEFORE_OR_AT_FIRST".equals(labelPosition)
                    || !hasDeterministicLabelBeforeClaim(answer, claims))) {
                violations.add("假设性内容首次出现前没有明确标注；模型判断位置为 "
                    + labelPosition + "，确定性前置标识检查未通过。");
            }
        }
        return new EvidenceReview(
            true, violations.stream().filter(value -> !value.isBlank()).distinct().toList(),
            List.copyOf(claims), "");
    }

    private boolean hasDeterministicLabelBeforeClaim(String answer, List<ClaimReview> claims) {
        String text = answer == null ? "" : answer;
        java.util.regex.Matcher marker = HYPOTHETICAL_LABEL.matcher(text);
        if (!marker.find()) return false;
        int firstClaim = claims.stream()
            .filter(claim -> "LABELED_HYPOTHETICAL".equals(claim.classification()))
            .mapToInt(claim -> text.indexOf(claim.claim()))
            .filter(index -> index >= 0)
            .min()
            .orElse(Math.max(1, text.length() / 4));
        return marker.start() <= firstClaim;
    }

    private JsonNode parseObject(String raw) throws Exception {
        String value = raw == null ? "" : raw.trim();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("evidence judge did not return JSON");
        }
        return objectMapper.readTree(value.substring(start, end + 1));
    }

    public record ClaimReview(String claim, String classification, String reason) {
        public ClaimReview {
            claim = claim == null ? "" : claim.trim();
            classification = classification == null ? "" : classification.trim();
            reason = reason == null ? "" : reason.trim();
        }
    }

    public record EvidenceReview(boolean evaluated,
                                 List<String> violations,
                                 List<ClaimReview> claims,
                                 String error) {
        public EvidenceReview {
            violations = violations == null ? List.of() : List.copyOf(violations);
            claims = claims == null ? List.of() : List.copyOf(claims);
            error = error == null ? "" : error.trim();
        }

        public static EvidenceReview error(String error) {
            return new EvidenceReview(false, List.of(), List.of(), error);
        }

        public boolean safe() {
            return evaluated && violations.isEmpty();
        }
    }
}
