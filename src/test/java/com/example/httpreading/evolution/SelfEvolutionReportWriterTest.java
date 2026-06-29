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
            false, true, List.of(FailureType.TOO_CONCEPTUAL, FailureType.EVIDENCE_BOUNDARY),
            List.of("评分项 concrete_explanation（0.00/1.00）：缺少具体场景"),
            List.of(new EvidenceIssue(
                EvidenceIssueType.UNLABELED_UNGROUNDED_SECTION,
                "历史场景缺少有效前置声明",
                5,
                List.of("例子一", "例子二"),
                "把无依据声明移到第一处想象内容之前")),
            List.of(
                new EvidenceBoundaryJudge.ClaimReview("例子一", "UNGROUNDED_DETAIL", "无证据"),
                new EvidenceBoundaryJudge.ClaimReview("例子二", "UNGROUNDED_DETAIL", "无证据"),
                new EvidenceBoundaryJudge.ClaimReview("例子三", "UNGROUNDED_DETAIL", "无证据")),
            10);
        SelfEvolutionReport.Aggregate aggregate =
            new SelfEvolutionReport.Aggregate(1, 0, 0, 0.25, 0.0);
        SelfEvolutionReport report = new SelfEvolutionReport(
            "run-1", "u1", List.of(), List.of(evalCase),
            List.of(result), List.of(result),
            PromptOverride.finalAnswerOnly("先声明无依据内容，再开始连续场景。"),
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
        assertTrue(jsonl.contains("\"evidence_use_mode\""));
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
        assertTrue(markdown.contains("证据用途"));
        assertTrue(markdown.contains("experiment valid: true"));
        assertTrue(markdown.contains("缺少具体场景"));
        assertTrue(markdown.contains("evolution strategy: BATCH_AGGREGATE"));
        assertTrue(markdown.contains("如何修改"));
        assertTrue(markdown.contains("例子一"));
        assertTrue(markdown.contains("例子二"));
        assertTrue(!markdown.contains("例子三"));
        assertTrue(markdown.contains("Effective FinalAnswer Evolvable Policy"));
        assertTrue(markdown.contains("先声明无依据内容，再开始连续场景。"));

        String json = Files.readString(output.resolve("report.json"));
        assertTrue(json.contains("\"evidenceClaims\""));
        assertTrue(json.contains("例子三"));
    }
}
