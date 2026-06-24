package com.example.httpreading.evaluation;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class EvaluationReportComparisonLiveTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void compareExplicitBaselineAndCandidateReports() throws Exception {
        String baselinePath = System.getProperty("evaluation.compare.baseline", "");
        String candidatePath = System.getProperty("evaluation.compare.candidate", "");
        assumeTrue(!baselinePath.isBlank() && !candidatePath.isBlank(),
            "Set evaluation.compare.baseline and evaluation.compare.candidate");
        EvaluationReport baseline = objectMapper.readValue(Path.of(baselinePath).toFile(), EvaluationReport.class);
        EvaluationReport candidate = objectMapper.readValue(Path.of(candidatePath).toFile(), EvaluationReport.class);
        EvaluationReportComparator.Comparison comparison = new EvaluationReportComparator().compare(baseline, candidate);
        Path output = Path.of(System.getProperty("evaluation.compare.output", "target/evaluation/comparison.json"));
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), comparison);
        System.out.printf("%n[EVALUATION_COMPARISON]%nbaseline=%.4f%ncandidate=%.4f%nimprovement=%+.4f%n",
            comparison.baselineScore(), comparison.candidateScore(), comparison.improvement());
    }
}
