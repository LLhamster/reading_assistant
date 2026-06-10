package com.example.httpreading.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.dto.ExternalMcpCall;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ExternalMcpAgentService {
    private static final int MAX_ROUNDS = 5;
    private static final int MAX_TOOL_CALLS = 5;
    private static final Set<String> TERMINAL_STATUSES = Set.of("complete", "needs_confirmation", "failed");
    private static final List<String> WRITE_PREFIXES = List.of(
        "add_", "create_", "delete_", "merge_", "push_", "update_", "remove_",
        "approve_", "submit_", "cancel_", "rerun_", "trigger_", "mark_", "assign_",
        "unassign_", "star_", "unstar_", "fork_");

    private final ExternalMcpClientService clientService;
    private final ExternalMcpToolPlannerService plannerService;
    private final ExternalMcpServerRouterService routerService;
    private final ObjectMapper objectMapper;
    private final PendingMcpInteractionStore pendingStore;

    @Autowired
    public ExternalMcpAgentService(ExternalMcpClientService clientService,
                                   ExternalMcpToolPlannerService plannerService,
                                   ExternalMcpServerRouterService routerService,
                                   ObjectMapper objectMapper,
                                   PendingMcpInteractionStore pendingStore) {
        this.clientService = clientService;
        this.plannerService = plannerService;
        this.routerService = routerService;
        this.objectMapper = objectMapper;
        this.pendingStore = pendingStore;
    }

    public ExternalMcpAgentResult execute(AiChatRequest request, String planningContext) {
        pendingStore.cancel(request.resolvedUserId(), request.resolvedSessionId());
        return run(new PendingMcpAgentState(copyRequest(request), planningContext));
    }

    public ExternalMcpAgentResult execute(AiChatRequest request, String planningContext, String routedServerName) {
        pendingStore.cancel(request.resolvedUserId(), request.resolvedSessionId());
        PendingMcpAgentState state = new PendingMcpAgentState(copyRequest(request), planningContext);
        state.setRoutedServerName(routedServerName);
        if (!state.getRoutedServerName().isBlank()) {
            state.getRefs().add("MCP_ROUTE " + state.getRoutedServerName() + ": selected by Planner");
        }
        return run(state);
    }

    public void cancelPending(AiChatRequest request) {
        pendingStore.cancel(request.resolvedUserId(), request.resolvedSessionId());
    }

    public ExternalMcpAgentResult resume(AiChatRequest confirmationRequest) {
        PendingMcpInteraction pending = pendingStore.consume(
            confirmationRequest.resolvedUserId(),
            confirmationRequest.resolvedSessionId(),
            confirmationRequest.getConfirmationId(),
            confirmationRequest.getSelectedOptionId(),
            confirmationRequest.getCustomAnswer());

        PendingMcpAgentState state = pending.getState();
        ExternalMcpClarification clarification = clarificationFrom(pending, confirmationRequest);
        rememberConfirmedRepositoryTarget(state, clarification);
        addClarificationObservation(state, clarification);
        state.getRefs().add("AUTO_CONFIRMATION " + concise(clarification.getText()));
        return run(state);
    }

    private ExternalMcpAgentResult run(PendingMcpAgentState state) {
        String routedServerName = state.getRoutedServerName();
        if (routedServerName.isBlank()) {
            List<Map<String, Object>> serverCandidates = clientService.routableServers();
            ExternalMcpServerRoute route = routerService.route(
                state.getOriginalRequest(), state.getPlanningContext(), serverCandidates);
            if (!route.isUseMcp()) {
                state.getRefs().add("MCP_ROUTE_SKIP: " + concise(route.getReason()));
                return new ExternalMcpAgentResult(
                    state.getResults(), state.getRefs(), "",
                    "completed", null, state.getOriginalRequest());
            }
            routedServerName = route.getServerName();
            state.setRoutedServerName(routedServerName);
            state.getRefs().add("MCP_ROUTE " + route.getServerName()
                + (route.getReason().isBlank() ? "" : ": " + concise(route.getReason())));
        }

        List<Map<String, Object>> allowedTools = clientService.allowedToolDescriptors(routedServerName);
        if (allowedTools.isEmpty()) {
            return new ExternalMcpAgentResult(
                state.getResults(), appendRef(state, "AUTO_PLAN_EMPTY"), "",
                "completed", null, state.getOriginalRequest());
        }

        Map<String, Map<String, Object>> descriptors = descriptorIndex(allowedTools);
        for (int round = state.getNextRound(); round <= MAX_ROUNDS; round++) {
            state.setNextRound(round);
            ExternalMcpAgentDecision decision = plannerService.decide(
                state.getOriginalRequest(),
                state.getPlanningContext(),
                allowedTools,
                state.getObservations(),
                round,
                MAX_TOOL_CALLS - state.getToolCalls());
            String status = normalize(decision.getStatus()).toLowerCase(Locale.ROOT);

            if (TERMINAL_STATUSES.contains(status)) {
                return terminalResult(status, decision, state);
            }
            if (!"call_tool".equals(status) || decision.getCall() == null) {
                state.getRefs().add("AUTO_FAILED invalid agent decision");
                return new ExternalMcpAgentResult(
                    state.getResults(), state.getRefs(), "Agent 返回了无效决策，已停止外部工具调用。",
                    "completed", null, state.getOriginalRequest());
            }

            ExternalMcpCall call = decision.getCall();
            String toolKey = key(call.getServerName(), call.getToolName());
            String summary = concise(decision.getReasoningSummary());
            state.getRefs().add("AUTO_ROUND " + round + " CALL " + toolKey
                + (summary.isBlank() ? "" : ": " + summary));
            applyConfirmedRepositoryTarget(state, call);

            String validationError = validateCall(call, descriptors.get(toolKey));
            String repositoryTarget = repositoryTarget(call);
            if (validationError.isBlank() && !repositoryTarget.isBlank()
                && state.getRejectedRepositoryTargets().contains(repositoryTarget)) {
                validationError = "该仓库目标此前已返回 Not Found，必须先根据工具结果解析准确的 owner/repo，不能通过修改分页等参数重试: "
                    + repositoryTarget;
            }
            if (!validationError.isBlank()) {
                ExternalMcpCallResult failure = ExternalMcpCallResult.failure(
                    normalize(call.getServerName()), normalize(call.getToolName()), validationError);
                state.getObservations().add(new ExternalMcpAgentObservation(round, call, failure));
                state.getRefs().add("AUTO_OBSERVE " + round + " FAIL " + concise(validationError));
                state.setNextRound(round + 1);
                continue;
            }

            String fingerprint = fingerprint(call);
            if (!state.getExecutedCalls().add(fingerprint)) {
                ExternalMcpCallResult failure = ExternalMcpCallResult.failure(
                    call.getServerName(), call.getToolName(), "重复工具调用已被阻止");
                state.getObservations().add(new ExternalMcpAgentObservation(round, call, failure));
                state.getRefs().add("AUTO_OBSERVE " + round + " FAIL duplicate call blocked");
                state.setNextRound(round + 1);
                continue;
            }
            if (state.getToolCalls() >= MAX_TOOL_CALLS) {
                state.getRefs().add("AUTO_LIMIT_REACHED");
                return new ExternalMcpAgentResult(
                    state.getResults(), state.getRefs(), "MCP Agent 已达到工具调用上限。",
                    "completed", null, state.getOriginalRequest());
            }

            ExternalMcpCallResult result = clientService.callTool(call);
            state.setToolCalls(state.getToolCalls() + 1);
            state.getResults().add(result);
            state.getObservations().add(new ExternalMcpAgentObservation(round, call, result));
            if (!result.isOk() && !repositoryTarget.isBlank() && isNotFound(result.getError())) {
                state.getRejectedRepositoryTargets().add(repositoryTarget);
            }
            state.getRefs().add("AUTO_OBSERVE " + round + " " + (result.isOk() ? "OK" : "FAIL")
                + resultSummary(result));
            state.setNextRound(round + 1);
            ExternalMcpAgentResult forcedConfirmation = forceConfirmationForAmbiguousRepositorySearch(state, call, result);
            if (forcedConfirmation != null) {
                return forcedConfirmation;
            }
        }

        state.getRefs().add("AUTO_LIMIT_REACHED");
        return new ExternalMcpAgentResult(
            state.getResults(), state.getRefs(), "MCP Agent 已达到最大反思轮数，未能确认目标已经完成。",
            "completed", null, state.getOriginalRequest());
    }

    private ExternalMcpAgentResult forceConfirmationForAmbiguousRepositorySearch(PendingMcpAgentState state,
                                                                                 ExternalMcpCall call,
                                                                                 ExternalMcpCallResult result) {
        if (!result.isOk() || !"search_repositories".equals(normalize(call.getToolName()))) {
            return null;
        }
        List<ExternalMcpAgentOption> options = repositoryOptionsFromSearchResult(result.getContent());
        if (options.size() <= 1) {
            return null;
        }

        String question = "搜索到多个可能的 GitHub 仓库，请选择你要查询的仓库，或输入更明确的 owner/repo。";
        state.getRefs().add("AUTO_NEEDS_CONFIRMATION multiple repository candidates");
        return new ExternalMcpAgentResult(
            state.getResults(),
            state.getRefs(),
            question,
            "needs_confirmation",
            pendingStore.create(
                state.getOriginalRequest().resolvedUserId(),
                state.getOriginalRequest().resolvedSessionId(),
                question,
                options,
                state),
            state.getOriginalRequest());
    }

    private List<ExternalMcpAgentOption> repositoryOptionsFromSearchResult(String content) {
        JsonNode root = parseJson(content);
        if (root == null || root.isMissingNode()) {
            return List.of();
        }
        JsonNode items = root.isArray() ? root : root.path("items");
        if (!items.isArray() && root.path("data").path("items").isArray()) {
            items = root.path("data").path("items");
        }
        if (!items.isArray()) {
            return List.of();
        }

        List<ExternalMcpAgentOption> options = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        int index = 1;
        for (JsonNode item : items) {
            String fullName = text(item.path("full_name"));
            if (fullName.isBlank()) {
                String owner = text(item.path("owner").path("login"));
                String repo = text(item.path("name"));
                fullName = owner.isBlank() || repo.isBlank() ? "" : owner + "/" + repo;
            }
            String[] parts = fullName.split("/", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank() || !seen.add(fullName)) {
                continue;
            }
            String description = firstNonBlank(
                text(item.path("description")),
                text(item.path("html_url")),
                "GitHub 仓库候选");
            options.add(new ExternalMcpAgentOption(
                "repo-" + index,
                fullName,
                description,
                Map.of("owner", parts[0], "repo", parts[1])));
            index++;
            if (options.size() >= 3) {
                break;
            }
        }
        return options;
    }

    private boolean isRepositorySearch(ExternalMcpCall call) {
        return call != null && "search_repositories".equals(normalize(call.getToolName()));
    }

    private JsonNode parseJson(String content) {
        String json = extractJson(content);
        if (json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private String extractJson(String content) {
        if (content == null) {
            return "";
        }
        String text = content.trim();
        if (text.startsWith("{") || text.startsWith("[")) {
            return text;
        }
        int objectStart = text.indexOf('{');
        int objectEnd = text.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            return text.substring(objectStart, objectEnd + 1);
        }
        int arrayStart = text.indexOf('[');
        int arrayEnd = text.lastIndexOf(']');
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            return text.substring(arrayStart, arrayEnd + 1);
        }
        return "";
    }

    private String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText("").trim();
    }

    private ExternalMcpAgentResult terminalResult(String status,
                                                  ExternalMcpAgentDecision decision,
                                                  PendingMcpAgentState state) {
        String detail = firstNonBlank(decision.getMessage(), decision.getAssessment(), decision.getReasoningSummary());
        if ("complete".equals(status)) {
            state.getRefs().add("AUTO_COMPLETE" + (detail.isBlank() ? "" : " " + concise(detail)));
            return new ExternalMcpAgentResult(
                state.getResults(), state.getRefs(), "",
                "completed", null, state.getOriginalRequest());
        }
        if ("needs_confirmation".equals(status)) {
            state.getRefs().add("AUTO_NEEDS_CONFIRMATION" + (detail.isBlank() ? "" : " " + concise(detail)));
            String question = detail.isBlank() ? "存在多个可能目标，请选择一个候选，或输入更明确的说明。" : detail;
            return new ExternalMcpAgentResult(
                state.getResults(),
                state.getRefs(),
                question,
                "needs_confirmation",
                pendingStore.create(
                    state.getOriginalRequest().resolvedUserId(),
                    state.getOriginalRequest().resolvedSessionId(),
                    question,
                    decision.getOptions(),
                    state),
                state.getOriginalRequest());
        }
        state.getRefs().add("AUTO_FAILED" + (detail.isBlank() ? "" : " " + concise(detail)));
        return new ExternalMcpAgentResult(
            state.getResults(), state.getRefs(), detail,
            "completed", null, state.getOriginalRequest());
    }

    private ExternalMcpClarification clarificationFrom(PendingMcpInteraction pending,
                                                       AiChatRequest confirmationRequest) {
        String selectedOptionId = normalize(confirmationRequest.getSelectedOptionId());
        if (!selectedOptionId.isBlank()) {
            ExternalMcpAgentOption selected = pending.getOptions().stream()
                .filter(option -> option.getId().equals(selectedOptionId))
                .findFirst()
                .orElseThrow();
            return new ExternalMcpClarification(
                "用户选择了候选：" + selected.getLabel()
                    + (selected.getDescription() == null || selected.getDescription().isBlank()
                    ? "" : "（" + selected.getDescription() + "）"),
                selected.getValue());
        }
        return new ExternalMcpClarification(
            "用户自定义澄清：" + normalize(confirmationRequest.getCustomAnswer()),
            Map.of("customAnswer", normalize(confirmationRequest.getCustomAnswer())));
    }

    private void rememberConfirmedRepositoryTarget(PendingMcpAgentState state,
                                                   ExternalMcpClarification clarification) {
        Object owner = clarification.getValue().get("owner");
        Object repo = clarification.getValue().get("repo");
        String normalizedOwner = owner == null ? "" : normalize(String.valueOf(owner));
        String normalizedRepo = repo == null ? "" : normalize(String.valueOf(repo));
        if (!normalizedOwner.isBlank() && !normalizedRepo.isBlank()) {
            state.setConfirmedRepositoryTarget(normalizedOwner, normalizedRepo);
            state.getRefs().add("AUTO_CONFIRMED_REPOSITORY " + normalizedOwner + "/" + normalizedRepo);
        }
    }

    private void applyConfirmedRepositoryTarget(PendingMcpAgentState state, ExternalMcpCall call) {
        if (!state.hasConfirmedRepositoryTarget() || call == null || call.getArguments() == null) {
            return;
        }
        Map<String, Object> arguments = new LinkedHashMap<>(call.getArguments());
        if (!arguments.containsKey("owner") || !arguments.containsKey("repo")) {
            return;
        }
        String currentOwner = normalize(String.valueOf(arguments.get("owner")));
        String currentRepo = normalize(String.valueOf(arguments.get("repo")));
        if (state.getConfirmedRepositoryOwner().equals(currentOwner)
            && state.getConfirmedRepositoryRepo().equals(currentRepo)) {
            return;
        }
        arguments.put("owner", state.getConfirmedRepositoryOwner());
        arguments.put("repo", state.getConfirmedRepositoryRepo());
        call.setArguments(arguments);
        state.getRefs().add("AUTO_REWRITE_REPOSITORY_TARGET "
            + currentOwner + "/" + currentRepo
            + " -> " + state.getConfirmedRepositoryOwner() + "/" + state.getConfirmedRepositoryRepo());
    }

    private void addClarificationObservation(PendingMcpAgentState state, ExternalMcpClarification clarification) {
        ExternalMcpCall call = new ExternalMcpCall();
        call.setServerName("user");
        call.setToolName("clarification");
        call.setArguments(Map.of("text", clarification.getText(), "value", clarification.getValue()));
        String content;
        try {
            content = objectMapper.writeValueAsString(Map.of(
                "text", clarification.getText(),
                "value", clarification.getValue()));
        } catch (JsonProcessingException exception) {
            content = clarification.getText();
        }
        state.getObservations().add(new ExternalMcpAgentObservation(
            state.getNextRound(), call, ExternalMcpCallResult.success("user", "clarification", content)));
    }

    private List<String> appendRef(PendingMcpAgentState state, String ref) {
        state.getRefs().add(ref);
        return state.getRefs();
    }

    private String validateCall(ExternalMcpCall call, Map<String, Object> descriptor) {
        String serverName = normalize(call.getServerName());
        String toolName = normalize(call.getToolName());
        if (serverName.isBlank() || toolName.isBlank()) {
            return "serverName 和 toolName 不能为空";
        }
        if (descriptor == null || !clientService.isToolAllowed(serverName, toolName)) {
            return "工具不存在、server 已禁用或不在 allowedTools 白名单中";
        }
        if (isWriteTool(toolName)) {
            return "自动 MCP Agent 禁止调用写操作工具";
        }
        List<String> missing = missingRequiredArguments(call.getArguments(), descriptor.get("inputSchema"));
        return missing.isEmpty() ? "" : "缺少必填参数: " + String.join(", ", missing);
    }

    private boolean isWriteTool(String toolName) {
        String normalized = normalize(toolName).toLowerCase(Locale.ROOT);
        return normalized.endsWith("_write")
            || WRITE_PREFIXES.stream().anyMatch(normalized::startsWith)
            || normalized.contains("comment") && !normalized.startsWith("get_") && !normalized.startsWith("list_");
    }

    private List<String> missingRequiredArguments(Map<String, Object> arguments, Object inputSchema) {
        Map<String, Object> schema = objectMap(inputSchema);
        Object requiredValue = schema.get("required");
        if (!(requiredValue instanceof List<?> required)) {
            return List.of();
        }
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        return required.stream()
            .map(String::valueOf)
            .filter(name -> !safeArguments.containsKey(name) || safeArguments.get(name) == null
                || safeArguments.get(name) instanceof String text && text.isBlank())
            .toList();
    }

    private Map<String, Object> objectMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        return objectMapper.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() {
        });
    }

    private Map<String, Map<String, Object>> descriptorIndex(List<Map<String, Object>> descriptors) {
        Map<String, Map<String, Object>> index = new LinkedHashMap<>();
        for (Map<String, Object> descriptor : descriptors) {
            index.put(key(String.valueOf(descriptor.get("serverName")),
                String.valueOf(descriptor.get("toolName"))), descriptor);
        }
        return index;
    }

    private String fingerprint(ExternalMcpCall call) {
        try {
            return key(call.getServerName(), call.getToolName()) + ":"
                + objectMapper.writeValueAsString(call.getArguments());
        } catch (JsonProcessingException exception) {
            return key(call.getServerName(), call.getToolName()) + ":" + String.valueOf(call.getArguments());
        }
    }

    private String repositoryTarget(ExternalMcpCall call) {
        if (call == null || call.getArguments() == null) {
            return "";
        }
        Object owner = call.getArguments().get("owner");
        Object repo = call.getArguments().get("repo");
        if (owner == null || repo == null) {
            return "";
        }
        String normalizedOwner = normalize(String.valueOf(owner)).toLowerCase(Locale.ROOT);
        String normalizedRepo = normalize(String.valueOf(repo)).toLowerCase(Locale.ROOT);
        return normalizedOwner.isBlank() || normalizedRepo.isBlank()
            ? ""
            : normalize(call.getServerName()) + ":" + normalizedOwner + "/" + normalizedRepo;
    }

    private boolean isNotFound(String error) {
        String normalized = error == null ? "" : error.toLowerCase(Locale.ROOT);
        return normalized.contains("404")
            || normalized.contains("not found")
            || normalized.contains("repository not found");
    }

    private String resultSummary(ExternalMcpCallResult result) {
        String text = result.isOk() ? result.getContent() : result.getError();
        return text == null || text.isBlank() ? "" : " " + concise(text);
    }

    private AiChatRequest copyRequest(AiChatRequest source) {
        AiChatRequest copy = new AiChatRequest();
        copy.setBookId(source.getBookId());
        copy.setChapterIndex(source.getChapterIndex());
        copy.setQuestion(source.getQuestion());
        copy.setUserId(source.getUserId());
        copy.setSessionId(source.getSessionId());
        copy.setContextId(source.getContextId());
        copy.setTopK(source.getTopK());
        copy.setEnableMemory(source.getEnableMemory());
        copy.setEnableRag(source.getEnableRag());
        copy.setEnableExternalMcp(source.getEnableExternalMcp());
        copy.setExternalMcpCalls(source.getExternalMcpCalls());
        copy.setChapterTitle(source.getChapterTitle());
        copy.setChapterContent(source.getChapterContent());
        return copy;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String concise(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 180 ? normalized : normalized.substring(0, 180) + "...";
    }

    private String key(String serverName, String toolName) {
        return normalize(serverName) + "/" + normalize(toolName);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
