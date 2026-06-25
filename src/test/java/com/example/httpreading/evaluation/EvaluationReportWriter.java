package com.example.httpreading.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;

final class EvaluationReportWriter {
    private final ObjectMapper objectMapper;

    EvaluationReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Path write(EvaluationReport report, Path root) throws IOException {
        Path directory = root.resolve(report.runId());
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("report.json"),
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("report.md"), markdown(report), StandardCharsets.UTF_8);
        return directory;
    }

    private String markdown(EvaluationReport report) {
        StringBuilder text = new StringBuilder()
            .append("# Reading Agent Evaluation\n\n")
            .append("- run: ").append(report.runId()).append('\n')
            .append("- suite: ").append(report.suite()).append('\n')
            .append("- target: ").append(report.target()).append('\n')
            .append("- split: ").append(report.split()).append('\n')
            .append("- score: ").append(format(report.score())).append('\n')
            .append("- passed: ").append(report.passed()).append('/').append(report.evaluated()).append('\n')
            .append("- unscored: ").append(report.unscored()).append('/').append(report.evaluated()).append('\n')
            .append("- pass rate: ").append(format(rate(report.passed(), report.evaluated()))).append('\n');
        if (EvaluationCases.TOOL_ROUTING.equals(report.suite())) {
            text.append("- exact match: ").append(format(report.exactMatch())).append('\n')
                .append("- tool F1: ").append(format(report.toolF1())).append('\n');
        } else {
            text.append("- criterion score: ").append(format(report.criterionScore())).append('\n')
                .append("- required item recall: ").append(format(report.requiredItemRecall())).append('\n')
                .append("- forbidden item hit rate: ").append(format(report.forbiddenItemHitRate())).append('\n')
                .append("- style compliance: ").append(format(report.styleCompliance())).append('\n');
        }
        text.append("\n## Failed cases\n\n");
        report.cases().stream().filter(result -> !result.passed()).forEach(result -> text
            .append("### ").append(result.id()).append(" — ").append(format(result.score())).append("\n\n")
            .append("Answer shape: ").append(result.answerShape()).append("\n\n")
            .append("Failure mode: ").append(result.failureMode()).append("\n\n")
            .append("Agent output: ").append(result.agentOutput()).append("\n\n")
            .append("Feedback: ").append(result.feedback()).append("\n\n")
            .append("Missing required items: ").append(result.missingRequiredItems()).append("\n\n")
            .append("Forbidden items hit: ").append(result.forbiddenItemsHit()).append("\n\n")
            .append("Style violations: ").append(result.styleViolations()).append("\n\n")
            .append(result.criterionScores().stream().map(score -> "- `" + score.id() + "`: "
                + format(score.score()) + "/" + format(score.maxScore()) + " — " + score.reason())
                .collect(java.util.stream.Collectors.joining("\n")))
            .append(result.policyViolations().isEmpty() ? "" : "\n\nPolicy violations: " + result.policyViolations())
            .append("\n\n"));
        return text.toString();
    }

    private double rate(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private String format(double value) {
        return "%.4f".formatted(value);
    }
}
