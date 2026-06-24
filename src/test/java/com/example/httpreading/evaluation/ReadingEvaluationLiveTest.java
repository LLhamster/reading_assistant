package com.example.httpreading.evaluation;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.example.httpreading.service.ModelClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** Optional real-model evaluation. Normal mvn test skips both methods. */
class ReadingEvaluationLiveTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonlEvaluationLoader loader = new JsonlEvaluationLoader(objectMapper);

    @Test
    void evaluateToolRoutingWithRealModel() throws Exception {
        assumeTrue(Boolean.getBoolean("evaluation.live.routing"),
            "Enable with -Devaluation.live.routing=true");
        String split = selectedSplit();
        ModelClient model = liveModelClient();
        List<EvaluationCases.EvaluationExample> cases = load("evaluation/tool-routing.jsonl");
        EvaluationReplayRunner runner = new EvaluationReplayRunner(objectMapper);
        EvaluationReport report = runner.run(cases, EvaluationCases.TOOL_ROUTING, split,
            "PLANNER_AND_SELF_LOCAL_COMPONENTS", "moonshot-v1-8k", mode(), evaluationLimit(),
            example -> predictRoute(model, example), deterministicJudge());
        write(report);
        System.out.printf("%n[TOOL_ROUTING_EVALUATION]%nsplit=%s%ntotal=%d%nscore=%.4f%nexactMatch=%.4f%nmodeAccuracy=%.4f%ntoolF1=%.4f%n",
            split, report.evaluated(), report.score(), report.exactMatch(), report.modeAccuracy(), report.toolF1());
        double threshold = Double.parseDouble(System.getProperty("evaluation.routing.minScore", "0.70"));
        assertTrue(report.score() >= threshold, "routing score below " + threshold + ": " + failed(report));
    }

    @Test
    void evaluateMultiTurnAnswersWithHybridJudge() throws Exception {
        assumeTrue(Boolean.getBoolean("evaluation.live.answer"),
            "Enable with -Devaluation.live.answer=true");
        String split = selectedSplit();
        ModelClient model = liveModelClient();
        List<EvaluationCases.EvaluationExample> cases = load("evaluation/multi-turn-reading-qa.jsonl");
        EvaluationReplayRunner runner = new EvaluationReplayRunner(objectMapper);
        EvaluationJudge judge = new LlmEvaluationJudge(model, objectMapper);
        EvaluationReport report = runner.run(cases, EvaluationCases.MULTI_TURN_QA, split,
            "FINAL_ANSWER_COMPONENT_REPLAY", "moonshot-v1-8k", mode(), evaluationLimit(),
            example -> predictAnswer(model, example), judge);
        write(report);
        System.out.printf("%n[MULTI_TURN_READING_EVALUATION]%nsplit=%s%ntotal=%d%nscore=%.4f%nevidenceRecall=%.4f%npassRate=%.4f%n",
            split, report.evaluated(), report.score(), report.evidenceRecall(), rate(report.passed(), report.evaluated()));
        double threshold = Double.parseDouble(System.getProperty("evaluation.answer.minScore", "0.75"));
        assertTrue(report.score() >= threshold, "answer score below " + threshold + ": " + failed(report));
    }

    private EvaluationReplayRunner.AgentResult predictRoute(ModelClient model,
                                                              EvaluationCases.EvaluationExample example) throws IOException {
        ObjectNode input = objectMapper.createObjectNode();
        input.set("task_input", objectMapper.valueToTree(example.taskInput()));
        String prompt = """
            你同时模拟阅读 Agent 一级 Planner 和 self-local 内部工具决策，只输出 JSON。
            plannerMode 只能是 NO_TOOL 或 BOUNDED_REACT；需要内部阅读工具时必须为 BOUNDED_REACT，plannerServer=self-local。
            tools 只能从 context.get_recent_dialogue、context.get_current_page、memory.search、
            profile.list_categories、profile.get_category_detail、profile.search_relevant、rag.search 中选择。
            问候、改写、创作和普通概念解释使用 NO_TOOL；ragEnabled=true 只表示允许，不表示必须检索。
            书籍原文用 rag.search；当前划词用 current_page；刚才的对话用 recent_dialogue；
            跨会话历史用 memory；理解水平和偏好用 profile。只选择不可缺少的工具。
            输出：{"plannerMode":"NO_TOOL|BOUNDED_REACT","plannerServer":"","tools":[]}
            输入：
            """ + objectMapper.writeValueAsString(input);
        long start = System.nanoTime();
        String raw = model.chat(prompt);
        long latency = (System.nanoTime() - start) / 1_000_000;
        JsonNode json = parseJson(raw);
        List<String> tools = strings(json.path("tools"));
        EvaluationMetrics.RoutingPrediction route = new EvaluationMetrics.RoutingPrediction(
            json.path("plannerMode").asText(), json.path("plannerServer").asText(), tools);
        EvaluationMetrics.ExecutionTrace trace = new EvaluationMetrics.ExecutionTrace(
            tools, List.of(), tools.size(), latency, 1, prompt.length(), raw.length());
        return new EvaluationReplayRunner.AgentResult(route, "", trace);
    }

    private EvaluationReplayRunner.AgentResult predictAnswer(ModelClient model,
                                                               EvaluationCases.EvaluationExample example) throws IOException {
        ObjectNode input = objectMapper.createObjectNode();
        input.set("task_input", objectMapper.valueToTree(example.taskInput()));
        String prompt = """
            你是阅读 Agent 的最终回答阶段。MCP 工具已经执行完毕，task_input 中包含全部多轮上下文、
            collected_evidence 和 mcp_results；禁止再次规划或调用工具。
            请承接历史对话、消解指代，并且只依据给定材料回答，不要编造材料外的书中事实。
            只输出 JSON，不要输出 Markdown。输出：{"answer":"回答正文"}
            输入：
            """ + objectMapper.writeValueAsString(input);
        long start = System.nanoTime();
        String raw = model.chat(prompt);
        long latency = (System.nanoTime() - start) / 1_000_000;
        JsonNode json = parseJson(raw);
        EvaluationMetrics.ExecutionTrace trace = new EvaluationMetrics.ExecutionTrace(
            List.of(), example.taskInput().collectedEvidence().stream().map(EvaluationCases.CollectedEvidence::id).toList(),
            0, latency, 1, prompt.length(), raw.length());
        return new EvaluationReplayRunner.AgentResult(
            new EvaluationMetrics.RoutingPrediction("", "", List.of()), json.path("answer").asText(), trace);
    }

    private List<EvaluationCases.EvaluationExample> load(String resource) throws IOException {
        return loader.load(resource, EvaluationCases.EvaluationExample.class);
    }

    private int evaluationLimit() {
        return Integer.parseInt(System.getProperty("evaluation.limit", Integer.toString(Integer.MAX_VALUE)));
    }

    private String selectedSplit() {
        String split = System.getProperty("evaluation.split", EvaluationCases.DEV).trim().toLowerCase();
        if (!SetHolder.SPLITS.contains(split)) {
            throw new IllegalArgumentException("evaluation.split must be dev or holdout");
        }
        if (EvaluationCases.HOLDOUT.equals(split)) {
            assumeTrue(Boolean.getBoolean("evaluation.allowHoldout"),
                "Holdout requires -Devaluation.allowHoldout=true");
        }
        return split;
    }

    private EvaluationJudge.Mode mode() {
        return EvaluationJudge.Mode.valueOf(System.getProperty("evaluation.mode", "FAST").toUpperCase());
    }

    private EvaluationJudge deterministicJudge() {
        return (example, prediction, rules, mode) -> EvaluationMetrics.JudgeScore.unscored("not used for routing");
    }

    private void write(EvaluationReport report) throws IOException {
        Path root = Path.of(System.getProperty("evaluation.reportDir", "target/evaluation"));
        Path output = new EvaluationReportWriter(objectMapper).write(report, root);
        System.out.println("Evaluation report: " + output.toAbsolutePath());
    }

    private JsonNode parseJson(String raw) throws IOException {
        String value = raw == null ? "" : raw.trim();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IOException("model did not return JSON: " + value);
        }
        return objectMapper.readTree(value.substring(start, end + 1));
    }

    private List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return List.copyOf(values);
    }

    private ModelClient liveModelClient() throws Exception {
        String apiKey = firstNonBlank(System.getProperty("model.apiKey"), System.getenv("MODEL_API_KEY"));
        assumeTrue(!apiKey.isBlank(), "Set -Dmodel.apiKey or MODEL_API_KEY");
        ModelClient modelClient = new ModelClient();
        Field field = ModelClient.class.getDeclaredField("apiKey");
        field.setAccessible(true);
        field.set(modelClient, apiKey);
        return modelClient;
    }

    private String failed(EvaluationReport report) {
        return report.cases().stream().filter(result -> !result.passed()).map(EvaluationReport.CaseResult::id).toList().toString();
    }

    private double rate(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static final class SetHolder {
        private static final java.util.Set<String> SPLITS = java.util.Set.of(EvaluationCases.DEV, EvaluationCases.HOLDOUT);
    }
}
