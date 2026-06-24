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
            .append("- pass rate: ").append(format(rate(report.passed(), report.evaluated()))).append('\n')
            .append("- exact match: ").append(format(report.exactMatch())).append('\n')
            .append("- tool F1: ").append(format(report.toolF1())).append('\n')
            .append("- evidence recall: ").append(format(report.evidenceRecall())).append("\n\n")
            .append("## Failed cases\n\n");
        report.cases().stream().filter(result -> !result.passed()).forEach(result -> text
            .append("- ").append(result.id()).append(": ").append(result.feedback())
            .append(result.failures().isEmpty() ? "" : " " + result.failures()).append('\n'));
        return text.toString();
    }

    private double rate(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private String format(double value) {
        return "%.4f".formatted(value);
    }
}
