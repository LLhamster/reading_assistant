package com.example.httpreading.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.dto.ExternalMcpCall;
import com.example.httpreading.service.ModelClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class ExternalMcpToolPlannerService {
    private static final int MAX_OBSERVATION_CONTENT_CHARS = 3500;
    private static final int MAX_OBSERVATIONS_JSON_CHARS = 9000;
    private final ModelClient modelClient;
    private final ObjectMapper objectMapper;

    public ExternalMcpToolPlannerService(ModelClient modelClient, ObjectMapper objectMapper) {
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
    }

    public ExternalMcpAgentDecision decide(AiChatRequest request,
                                           String planningContext,
                                           List<Map<String, Object>> allowedTools,
                                           List<ExternalMcpAgentObservation> observations,
                                           int round,
                                           int remainingCalls) {
        String rawDecision = modelClient.chat(buildPrompt(
            request, planningContext, allowedTools, observations, round, remainingCalls));
        Map<String, Object> root = parseDecision(rawDecision);
        if (root.isEmpty()) {
            return ExternalMcpAgentDecision.failed("AUTO_PLAN_PARSE_FAILED");
        }

        String status = normalize(root.get("status"));
        String assessment = normalize(root.get("assessment"));
        String reasoningSummary = normalize(root.get("reasoningSummary"));
        String message = normalize(root.get("message"));
        ExternalMcpCall call = parseCall(root.get("call"));
        List<ExternalMcpAgentOption> options = parseOptions(root.get("options"));
        return new ExternalMcpAgentDecision(status, assessment, reasoningSummary, call, message, options);
    }

    private String buildPrompt(AiChatRequest request,
                               String planningContext,
                               List<Map<String, Object>> allowedTools,
                               List<ExternalMcpAgentObservation> observations,
                               int round,
                               int remainingCalls) {
        return """
            你是一个只读 MCP Agent 的单轮决策器。你不回答用户问题，只决定当前这一轮的下一步。
            你必须只返回严格 JSON，不要 Markdown，不要输出完整思维过程。

            工作方式：
            - 根据用户原始目标、allowedTools 及之前工具返回的 observations，判断目标是否已经完成。
            - 如果工具失败、参数不完整或目标名称可能不准确，请分析 observation，并从 allowedTools 中自主选择可能有效的下一步；不要依赖固定工具顺序。
            - 工具结果成功不代表用户目标一定完成；只有已经取得回答目标所需的信息时才返回 complete。
            - 每轮最多选择一个工具。只能调用 allowedTools 中的只读工具，并严格按照 inputSchema 生成参数。
            - 对通用网页搜索：优先用 web_search 获取候选结果；如果搜索摘要、URL、发布时间和来源足以回答，就返回 complete；如果摘要不足或需要核验具体网页内容，再调用 web_fetch 读取 URL。
            - 当多个候选都合理、无法可靠确定用户指的是哪一个时，返回 needs_confirmation，并在 options 中提供最多 3 个候选。
            - 如果仍有可行的只读探索方法，不要过早返回 failed。
            - 不要重复 observations 中已经执行过的相同工具和相同参数。

            资源标识约束：
            - owner、repo、路径、编号、SHA 等资源标识必须来自用户明确提供的值，或来自成功 Observation 中的准确字段；禁止凭常识猜测。
            - 用户只给出仓库简称而没有 owner 时，表示目标尚未解析。应从 allowedTools 中选择能发现真实资源标识的工具，而不是直接编造 owner。
            - repository not found、404、Not Found 表示该 owner/repo 假设已被证伪。后续不得通过修改分页、分支等无关参数继续尝试同一 owner/repo。
            - 搜索结果出现 full_name 或 owner/name 时，后续仓库工具必须原样使用其中的 owner 和 repo，不能继续使用旧猜测。
            - 如果搜索结果只有一个与用户简称明显匹配的候选，可以继续验证；如果有多个合理候选，必须返回 needs_confirmation。
            - 不要为了回答一个未明确归属的仓库简称，依次查询多个不同 owner 的同名仓库；这属于目标歧义，应请求用户确认。
            - 选择工具必须符合其 description：代码搜索不能代替仓库发现或提交历史查询。

            status 规则：
            - call_tool：需要继续调用一个工具，此时 call 必须存在。
            - complete：工具结果已经满足用户目标，call 必须为 null。
            - needs_confirmation：存在无法安全消除的歧义，call 必须为 null，options 应包含候选。
            - failed：没有允许的工具可以继续完成目标，call 必须为 null。

            用户原始目标：
            %s

            规划上下文：
            %s

            当前轮次：%d
            剩余工具调用额度：%d

            allowedTools：
            %s

            observations：
            %s

            needs_confirmation 的 option 规则：
            - id 使用 repo-1、repo-2 这类稳定短 ID。
            - label 是用户可读候选名称，例如 owner/repo。
            - value 放后续工具需要复用的结构化参数，例如 {"owner":"psanford","repo":"httpread"}。
            - description 用一句话说明为什么这是候选。

            返回格式：
            {"status":"call_tool|complete|needs_confirmation|failed","assessment":"简短目标状态判断","reasoningSummary":"简短下一步理由，不输出详细思维链","call":{"serverName":"...","toolName":"...","arguments":{}},"message":"","options":[{"id":"repo-1","label":"owner/repo","description":"候选说明","value":{"owner":"owner","repo":"repo"}}]}
            """.formatted(
            request == null ? "" : request.getQuestion(),
            planningContext == null ? "" : planningContext,
            round,
            remainingCalls,
            toJson(allowedTools),
            compactObservations(observations));
    }

    private String compactObservations(List<ExternalMcpAgentObservation> observations) {
        if (observations == null || observations.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> compact = observations.stream()
            .map(ExternalMcpAgentObservation::toMap)
            .map(this::truncateObservation)
            .toList();
        String json = toJson(compact);
        if (json.length() <= MAX_OBSERVATIONS_JSON_CHARS) {
            return json;
        }
        return json.substring(0, MAX_OBSERVATIONS_JSON_CHARS)
            + "...[observations truncated]";
    }

    private Map<String, Object> truncateObservation(Map<String, Object> observation) {
        Map<String, Object> compact = new LinkedHashMap<>(observation);
        compact.computeIfPresent("content", (key, value) -> truncate(String.valueOf(value)));
        compact.computeIfPresent("error", (key, value) -> truncate(String.valueOf(value)));
        return compact;
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_OBSERVATION_CONTENT_CHARS) {
            return value;
        }
        return value.substring(0, MAX_OBSERVATION_CONTENT_CHARS) + "...[tool result truncated]";
    }

    private ExternalMcpCall parseCall(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return null;
        }
        Map<String, Object> map = objectMap(rawMap);
        ExternalMcpCall call = new ExternalMcpCall();
        call.setServerName(normalize(map.get("serverName")));
        call.setToolName(normalize(map.get("toolName")));
        call.setArguments(map.get("arguments") instanceof Map<?, ?> arguments
            ? objectMap(arguments)
            : Map.of());
        return call;
    }

    private List<ExternalMcpAgentOption> parseOptions(Object value) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        return rawList.stream()
            .filter(Map.class::isInstance)
            .map(Map.class::cast)
            .map(this::parseOption)
            .filter(option -> !option.getLabel().isBlank())
            .limit(3)
            .toList();
    }

    private ExternalMcpAgentOption parseOption(Map<?, ?> rawMap) {
        Map<String, Object> map = objectMap(rawMap);
        ExternalMcpAgentOption option = new ExternalMcpAgentOption();
        option.setId(normalize(map.get("id")));
        option.setLabel(normalize(map.get("label")));
        option.setDescription(normalize(map.get("description")));
        option.setValue(map.get("value") instanceof Map<?, ?> value
            ? objectMap(value)
            : Map.of());
        return option;
    }

    private Map<String, Object> parseDecision(String rawDecision) {
        String json = extractJson(rawDecision);
        if (json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private String extractJson(String rawDecision) {
        if (rawDecision == null) {
            return "";
        }
        String text = rawDecision.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start < 0 || end <= start ? "" : text.substring(start, end + 1);
    }

    private Map<String, Object> objectMap(Map<?, ?> rawMap) {
        Map<String, Object> data = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> data.put(String.valueOf(key), value));
        return data;
    }

    private String normalize(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "[]";
        }
    }
}
