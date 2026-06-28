package com.example.httpreading.evolution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.stereotype.Component;

@Component
public class SelfEvolutionReportWriter {
    private final ObjectMapper objectMapper;

    public SelfEvolutionReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Path write(SelfEvolutionReport report, Path root) throws IOException {
        Path directory = root.resolve(report.runId());
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("report.json"),
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
            StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("report.md"), markdown(report), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("eval-cases.jsonl"), casesJsonl(report),
            StandardCharsets.UTF_8);
        return directory;
    }

    private String casesJsonl(SelfEvolutionReport report) {
        ObjectMapper snakeCaseMapper = objectMapper.copy()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return report.evalCases().stream().map(evalCase -> {
            try {
                return snakeCaseMapper.writeValueAsString(datasetShape(evalCase));
            } catch (Exception exception) {
                throw new IllegalStateException("cannot serialize evolution eval case " + evalCase.id(), exception);
            }
        }).collect(Collectors.joining("\n", "", "\n"));
    }

    private Map<String, Object> datasetShape(EvolutionEvalCase evalCase) {
        Map<String, Object> taskInput = new LinkedHashMap<>();
        taskInput.put("question", evalCase.request().getQuestion());
        taskInput.put("context", Map.of(
            "user_id", evalCase.request().resolvedUserId(),
            "session_id", evalCase.request().resolvedSessionId(),
            "book_id", evalCase.request().getBookId(),
            "chapter_index", evalCase.request().getChapterIndex(),
            "selected_context", nullToEmpty(evalCase.request().getSelectedContext()),
            "memory_enabled", evalCase.request().isMemoryEnabled(),
            "rag_enabled", evalCase.request().isRagEnabled()));
        taskInput.put("dialogue", evalCase.dialogue());
        taskInput.put("collected_evidence", evalCase.collectedEvidence());
        taskInput.put("mcp_results", evalCase.mcpResults());
        taskInput.put("final_answer_input", evalCase.finalAnswerInput());

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("id", evalCase.id());
        output.put("suite", "MULTI_TURN_QA");
        output.put("task_input", taskInput);
        output.put("expected_behavior", evalCase.expectedBehavior());
        output.put("difficulty", evalCase.difficulty());
        output.put("category", evalCase.category());
        output.put("source", "self_evolution");
        output.put("split", "dev");
        output.put("provenance", Map.of(
            "signal_id", evalCase.signalId(),
            "expected_failure_type", evalCase.expectedFailureType().name(),
            "reviewed", false));
        return output;
    }

    private String markdown(SelfEvolutionReport report) {
        StringBuilder text = new StringBuilder()
            .append("# Self-Evolution Experiment\n\n")
            .append("- run: ").append(report.runId()).append('\n')
            .append("- user: ").append(report.userId()).append('\n')
            .append("- signals: ").append(report.signals().size()).append('\n')
            .append("- eval cases: ").append(report.evalCases().size()).append('\n')
            .append("- baseline score: ").append(format(report.baseline().averageScore())).append('\n')
            .append("- baseline pass rate: ").append(format(report.baseline().passRate())).append('\n')
            .append("- candidate evaluated: ").append(!report.candidateResults().isEmpty()).append('\n');
        if (!report.candidateResults().isEmpty()) {
            text.append("- candidate score: ").append(format(report.candidate().averageScore())).append('\n')
                .append("- candidate pass rate: ").append(format(report.candidate().passRate())).append('\n');
        }
        text
            .append("- experiment valid: ").append(report.experimentValid()).append('\n')
            .append("- candidate better: ").append(report.candidateBetter()).append("\n\n")
            .append("- generated dataset: eval-cases.jsonl\n\n")
            .append("## Recommendation\n\n")
            .append(report.recommendation()).append("\n\n")
            .append("## Candidate FinalAnswer Evolvable Patch\n\n")
            .append("固定契约未修改；以下内容仅追加到 FinalAnswer 的可进化策略区。\n\n```text\n")
            .append(report.candidatePrompt().finalAnswerPatch()).append("\n```\n\n")
            .append("## Failed Case Comparison\n\n");
        for (int index = 0; index < report.evalCases().size(); index++) {
            EvolutionCaseResult baseline = report.baselineResults().get(index);
            if (report.candidateResults().isEmpty()) {
                if (!baseline.passed()) {
                    appendCaseHeader(text, report.evalCases().get(index));
                    appendResult(text, "Baseline", baseline);
                }
                continue;
            }
            EvolutionCaseResult candidate = report.candidateResults().get(index);
            if (baseline.passed() && candidate.passed()) continue;
            appendCaseHeader(text, report.evalCases().get(index));
            appendResult(text, "Baseline", baseline);
            appendResult(text, "Candidate", candidate);
        }
        return text.toString();
    }

    private void appendCaseHeader(StringBuilder text, EvolutionEvalCase evalCase) {
        text.append("### ").append(evalCase.id()).append("\n\n")
            .append("**问题**：").append(evalCase.request().getQuestion()).append("\n\n")
            .append("**理想回答评分项**：\n\n");
        for (EvolutionEvalCase.ScoringCriterion criterion
            : evalCase.expectedBehavior().scoringCriteria()) {
            text.append("- `").append(criterion.id()).append("`（")
                .append(format(criterion.score())).append(" 分）：")
                .append(criterion.description()).append('\n');
        }
        text.append('\n');
    }

    private void appendResult(StringBuilder text,
                              String label,
                              EvolutionCaseResult result) {
        text.append("#### ").append(label).append("\n\n")
            .append("- 得分：").append(format(result.score())).append('\n')
            .append("- 状态：").append(result.status()).append('\n')
            .append("- 硬失败：").append(result.hardFailure()).append('\n')
            .append("- 失败类型：").append(result.failureTypes()).append('\n')
            .append("- Evidence Judge：").append(evidenceStatus(result)).append("\n\n")
            .append("**回答摘要**：\n\n> ")
            .append(markdownQuote(truncate(result.answer(), 1200))).append("\n\n")
            .append("**内容评分原因**：\n\n");
        List<String> contentReasons = result.reasons().stream()
            .filter(reason -> !reason.startsWith("证据边界：")
                && !reason.startsWith("评测器错误："))
            .toList();
        if (contentReasons.isEmpty()) {
            text.append("- 无\n\n");
        } else {
            for (String reason : contentReasons) {
                text.append("- ").append(reason.replace('\n', ' ')).append('\n');
            }
            text.append('\n');
        }
        text.append("**证据边界原因**：\n\n");
        List<String> evidenceReasons = result.reasons().stream()
            .filter(reason -> reason.startsWith("证据边界："))
            .toList();
        if (evidenceReasons.isEmpty()) {
            text.append("- 无\n\n");
        } else {
            for (String reason : evidenceReasons) {
                text.append("- ").append(reason.substring("证据边界：".length()).replace('\n', ' '))
                    .append('\n');
            }
            text.append('\n');
        }
        List<String> evaluationErrors = result.reasons().stream()
            .filter(reason -> reason.startsWith("评测器错误："))
            .toList();
        if (!evaluationErrors.isEmpty()) {
            text.append("**评测器错误**：\n\n");
            for (String error : evaluationErrors) {
                text.append("- ").append(error.substring("评测器错误：".length()).replace('\n', ' '))
                    .append('\n');
            }
            text.append('\n');
        }
    }

    private String evidenceStatus(EvolutionCaseResult result) {
        if (result.failureTypes().contains(FailureType.EVALUATION_ERROR)) return "ERROR";
        if (result.failureTypes().contains(FailureType.EVIDENCE_BOUNDARY)) return "FAILED";
        return "PASSED";
    }

    private String format(double value) {
        return "%.4f".formatted(value);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String value, int limit) {
        String safe = value == null ? "" : value;
        return safe.length() <= limit ? safe : safe.substring(0, limit) + "…";
    }

    private String markdownQuote(String value) {
        return value.isBlank() ? "（空回答）" : value.replace("\n", "\n> ");
    }
}
