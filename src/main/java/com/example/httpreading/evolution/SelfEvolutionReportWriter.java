package com.example.httpreading.evolution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.example.httpreading.service.ai.FinalAnswerService;
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
        output.put("reading_boundary", evalCase.boundarySpec());
        output.put("source", "self_evolution");
        output.put("split", "dev");
        output.put("provenance", Map.of(
            "signal_id", evalCase.signalId(),
            "boundary_id", evalCase.boundarySpec().boundary().name(),
            "evidence_completeness", evalCase.boundarySpec().evidenceCompleteness().name(),
            "conversation_state", evalCase.boundarySpec().conversationState().name(),
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
            .append("- evolution strategy: BATCH_AGGREGATE\n")
            .append("- baseline score: ").append(format(report.baseline().averageScore())).append('\n')
            .append("- baseline pass rate: ").append(format(report.baseline().passRate())).append('\n')
            .append("- candidate evaluated: ").append(!report.candidateResults().isEmpty()).append('\n');
        if (!report.candidateResults().isEmpty()) {
            text.append("- candidate score: ").append(format(report.candidate().averageScore())).append('\n')
                .append("- candidate pass rate: ").append(format(report.candidate().passRate())).append('\n');
        }
        text
            .append("- experiment valid: ").append(report.experimentValid()).append('\n')
            .append("- candidate better: ").append(report.candidateBetter()).append('\n')
            .append("- candidate iterations: ").append(report.iterations().size()).append('\n')
            .append("- selected iteration: ")
            .append(report.selectedIteration() == null ? "none" : report.selectedIteration())
            .append('\n')
            .append("- stop reason: ").append(report.stopReason().isBlank() ? "n/a" : report.stopReason())
            .append("\n\n")
            .append("- generated dataset: eval-cases.jsonl\n\n")
            .append(boundaryCoverage(report.evalCases()))
            .append(iterationSummary(report))
            .append("## Recommendation\n\n")
            .append(report.recommendation()).append("\n\n")
            .append("## Candidate FinalAnswer Evolvable Patch\n\n")
            .append(report.candidateBetter()
                ? "以下是严格胜出的候选；固定契约未修改。\n\n"
                : "以下是最佳尝试候选，未自动采用；固定契约未修改。\n\n")
            .append("```text\n")
            .append(report.candidatePrompt().finalAnswerPatch()).append("\n```\n\n")
            .append("## Effective FinalAnswer Evolvable Policy\n\n")
            .append("以下是默认可进化策略追加候选 patch 后，本轮 candidate 实际使用的完整策略区。")
            .append("\n\n```text\n")
            .append(FinalAnswerService.effectiveEvolvablePolicy(
                report.candidatePrompt().finalAnswerPatch()))
            .append("\n```\n\n")
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

    private String iterationSummary(SelfEvolutionReport report) {
        if (report.iterations().isEmpty()) return "";
        StringBuilder text = new StringBuilder()
            .append("## Candidate Iterations\n\n")
            .append("| Iteration | Score | Pass rate | Hard failures | Safety | Fixed | Persistent | Regressions | Winner |\n")
            .append("|---:|---:|---:|---:|---|---:|---:|---:|---|\n");
        for (EvolutionIterationResult iteration : report.iterations()) {
            text.append("| ").append(iteration.iteration())
                .append(" | ").append(format(iteration.aggregate().averageScore()))
                .append(" | ").append(format(iteration.aggregate().passRate()))
                .append(" | ").append(iteration.aggregate().hardFailures())
                .append(" | ").append(iteration.safetyPassed())
                .append(" | ").append(iteration.fixedCaseIds().size())
                .append(" | ").append(iteration.persistentFailureCaseIds().size())
                .append(" | ").append(iteration.regressionCaseIds().size())
                .append(" | ").append(iteration.beatsBaseline())
                .append(" |\n");
        }
        text.append('\n');
        for (EvolutionIterationResult iteration : report.iterations()) {
            text.append("### Iteration ").append(iteration.iteration()).append(" Patch\n\n")
                .append("- fixed: ").append(iteration.fixedCaseIds()).append('\n')
                .append("- persistent: ").append(iteration.persistentFailureCaseIds()).append('\n')
                .append("- regressions: ").append(iteration.regressionCaseIds()).append("\n\n")
                .append("```text\n")
                .append(iteration.prompt().finalAnswerPatch())
                .append("\n```\n\n");
        }
        return text.toString();
    }

    private String boundaryCoverage(List<EvolutionEvalCase> cases) {
        Map<ReadingBoundary, List<EvolutionEvalCase>> byBoundary = cases.stream()
            .collect(Collectors.groupingBy(
                evalCase -> evalCase.boundarySpec().boundary(),
                LinkedHashMap::new,
                Collectors.toList()));
        StringBuilder text = new StringBuilder()
            .append("## Reading Boundary Coverage\n\n")
            .append("- covered boundaries: ")
            .append(byBoundary.size()).append('/').append(ReadingBoundary.values().length)
            .append("\n\n")
            .append("| Boundary | Cases | Evidence modes | Completeness | Conversation states |\n")
            .append("|---|---:|---|---|---|\n");
        for (ReadingBoundary boundary : ReadingBoundary.values()) {
            List<EvolutionEvalCase> matching = byBoundary.getOrDefault(boundary, List.of());
            text.append("| ").append(boundary).append(" | ").append(matching.size()).append(" | ")
                .append(joinDistinct(matching.stream().map(evalCase ->
                    evalCase.expectedBehavior().evidencePolicy().evidenceUseMode().name()).toList()))
                .append(" | ")
                .append(joinDistinct(matching.stream().map(evalCase ->
                    evalCase.boundarySpec().evidenceCompleteness().name()).toList()))
                .append(" | ")
                .append(joinDistinct(matching.stream().map(evalCase ->
                    evalCase.boundarySpec().conversationState().name()).toList()))
                .append(" |\n");
        }
        return text.append('\n').toString();
    }

    private String joinDistinct(List<String> values) {
        String joined = values.stream().distinct().collect(Collectors.joining(", "));
        return joined.isBlank() ? "—" : joined;
    }

    private void appendCaseHeader(StringBuilder text, EvolutionEvalCase evalCase) {
        text.append("### ").append(evalCase.id()).append("\n\n")
            .append("**问题**：").append(evalCase.request().getQuestion()).append("\n\n")
            .append("**证据用途**：")
            .append(evalCase.expectedBehavior().evidencePolicy().evidenceUseMode())
            .append("\n\n")
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
        if (!result.evidenceIssues().isEmpty()) {
            for (EvidenceIssue issue : result.evidenceIssues()) {
                text.append("- `").append(issue.type()).append("`（")
                    .append(issue.count()).append(" 处）：")
                    .append(issue.summary().replace('\n', ' ')).append('\n')
                    .append("  - 如何修改：")
                    .append(issue.correction().replace('\n', ' ')).append('\n');
                if (!issue.examples().isEmpty()) {
                    text.append("  - 代表例子：")
                        .append(issue.examples().stream()
                            .map(value -> "“" + truncate(value.replace('\n', ' '), 180) + "”")
                            .collect(Collectors.joining("；")))
                        .append('\n');
                }
            }
            text.append('\n');
        } else {
            List<String> evidenceReasons = result.reasons().stream()
                .filter(reason -> reason.startsWith("证据边界："))
                .toList();
            if (evidenceReasons.isEmpty()) {
                text.append("- 无\n\n");
            } else {
                for (String reason : evidenceReasons) {
                    text.append("- ")
                        .append(reason.substring("证据边界：".length()).replace('\n', ' '))
                        .append('\n');
                }
                text.append('\n');
            }
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
