package com.example.httpreading.evolution;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelfEvolutionReportWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void writesMultiTurnCompatibleJsonlDataset() throws Exception {
        EvolutionEvalCase evalCase = new EvalCaseGenerator()
            .generate(List.of(), "u1", 1L, 1).get(0);
        EvolutionCaseResult result = new EvolutionCaseResult(
            evalCase.id(), "回答摘要正文", "completed", null, 0.25,
            false, false, List.of(FailureType.TOO_CONCEPTUAL),
            List.of("评分项 concrete_explanation（0.00/1.00）：缺少具体场景"), 10);
        SelfEvolutionReport.Aggregate aggregate =
            new SelfEvolutionReport.Aggregate(1, 0, 0, 0.25, 0.0);
        SelfEvolutionReport report = new SelfEvolutionReport(
            "run-1", "u1", List.of(), List.of(evalCase),
            List.of(result), List.of(result), PromptOverride.none(),
            aggregate, aggregate, true, false, "保留 baseline");

        Path output = new SelfEvolutionReportWriter(new ObjectMapper()).write(report, tempDir);
        String jsonl = Files.readString(output.resolve("eval-cases.jsonl"));

        assertTrue(jsonl.contains("\"suite\":\"MULTI_TURN_QA\""));
        assertTrue(jsonl.contains("\"task_input\""));
        assertTrue(jsonl.contains("\"dialogue\""));
        assertTrue(jsonl.contains("\"collected_evidence\""));
        assertTrue(jsonl.contains("\"mcp_results\""));
        assertTrue(jsonl.contains("\"expected_behavior\""));
        assertTrue(jsonl.contains("\"scoring_criteria\""));
        assertTrue(jsonl.contains("\"evidence_policy\""));
        assertTrue(jsonl.contains("\"final_answer_input\""));
        assertTrue(!jsonl.contains("\"must_include\""));
        assertTrue(!jsonl.contains("\"must_not_include\""));
        assertTrue(!jsonl.contains("\"style_constraints\""));

        String markdown = Files.readString(output.resolve("report.md"));
        assertTrue(markdown.contains("理想回答评分项"));
        assertTrue(markdown.contains("回答摘要正文"));
        assertTrue(markdown.contains("内容评分原因"));
        assertTrue(markdown.contains("证据边界原因"));
        assertTrue(markdown.contains("Evidence Judge"));
        assertTrue(markdown.contains("experiment valid: true"));
        assertTrue(markdown.contains("缺少具体场景"));
    }
}
