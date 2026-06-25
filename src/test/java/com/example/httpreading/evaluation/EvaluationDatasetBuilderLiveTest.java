package com.example.httpreading.evaluation;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

import com.example.httpreading.service.ModelClient;
import com.example.httpreading.service.ai.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class EvaluationDatasetBuilderLiveTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EvaluationCandidateFactory factory = new EvaluationCandidateFactory(objectMapper);

    @Test
    void generateSyntheticCandidates() throws Exception {
        assumeTrue(Boolean.getBoolean("evaluation.generate.synthetic"),
            "Enable with -Devaluation.generate.synthetic=true");
        ModelClient model = liveModelClient();
        int count = Integer.parseInt(System.getProperty("evaluation.generate.count", "5"));
        String suite = System.getProperty("evaluation.generate.suite", EvaluationCases.TOOL_ROUTING);
        String objectSpec = "可用工具：" + new ToolRegistry().enabledTools();
        List<EvaluationCases.EvaluationExample> candidates = factory.synthetic(suite, objectSpec, count, model::chat);
        validateAndWrite(candidates, Path.of("target/evaluation-candidates/synthetic.jsonl"));
    }

    @Test
    void importSessionDbCandidates() throws Exception {
        assumeTrue(Boolean.getBoolean("evaluation.generate.sessiondb"),
            "Enable with -Devaluation.generate.sessiondb=true");
        String input = System.getProperty("evaluation.sessiondb.input", "");
        assumeTrue(!input.isBlank(), "Set -Devaluation.sessiondb.input=/path/to/sessions.jsonl");
        ModelClient model = liveModelClient();
        List<EvaluationCases.EvaluationExample> candidates = factory.sessionDb(Path.of(input), model::chat);
        validateAndWrite(candidates, Path.of("target/evaluation-candidates/sessiondb.jsonl"));
    }

    private void validateAndWrite(List<EvaluationCases.EvaluationExample> candidates, Path output) throws Exception {
        List<String> failures = new EvaluationDatasetValidator(objectMapper).validate(candidates);
        if (!failures.isEmpty()) {
            throw new IllegalStateException("generated candidates failed validation: " + failures);
        }
        factory.writeCandidates(candidates, output);
        System.out.println("Evaluation candidates written to " + output.toAbsolutePath());
    }

    private ModelClient liveModelClient() throws Exception {
        String apiKey = firstNonBlank(System.getProperty("model.apiKey"), System.getenv("MODEL_API_KEY"));
        assumeTrue(!apiKey.isBlank(), "Set -Dmodel.apiKey or MODEL_API_KEY");
        ModelClient modelClient = new ModelClient();
        setField(modelClient, "apiKey", apiKey);
        setField(modelClient, "baseUrl", firstNonBlank(System.getProperty("model.baseUrl"), System.getenv("MODEL_BASE_URL"),
            "https://api.deepseek.com/chat/completions"));
        setField(modelClient, "chatModel", firstNonBlank(System.getProperty("model.chatModel"), System.getenv("MODEL_CHAT_MODEL"),
            System.getProperty("model.chat.model"), "deepseek-chat"));
        return modelClient;
    }

    private void setField(ModelClient modelClient, String name, String value) throws Exception {
        Field field = ModelClient.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(modelClient, value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
