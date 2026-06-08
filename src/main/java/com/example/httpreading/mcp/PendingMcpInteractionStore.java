package com.example.httpreading.mcp;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.example.httpreading.api.ErrorCode;
import com.example.httpreading.dto.AiChatInteraction;
import com.example.httpreading.dto.AiChatInteractionOption;
import org.springframework.stereotype.Component;

@Component
public class PendingMcpInteractionStore {
    private static final int DEFAULT_TTL_MINUTES = 30;

    private final Map<String, PendingMcpInteraction> byConfirmationId = new ConcurrentHashMap<>();
    private final Map<String, String> bySessionKey = new ConcurrentHashMap<>();

    public synchronized AiChatInteraction create(String userId,
                                                 String sessionId,
                                                 String question,
                                                 List<ExternalMcpAgentOption> options,
                                                 PendingMcpAgentState state) {
        cleanupExpired();
        cancel(userId, sessionId);

        String confirmationId = UUID.randomUUID().toString();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(DEFAULT_TTL_MINUTES);
        List<ExternalMcpAgentOption> safeOptions = normalizeOptions(options);
        PendingMcpInteraction pending = new PendingMcpInteraction(
            confirmationId, userId, sessionId, question, safeOptions, true, expiresAt, state);
        byConfirmationId.put(confirmationId, pending);
        bySessionKey.put(sessionKey(userId, sessionId), confirmationId);
        return toDto(pending);
    }

    public synchronized PendingMcpInteraction consume(String userId,
                                                      String sessionId,
                                                      String confirmationId,
                                                      String selectedOptionId,
                                                      String customAnswer) {
        cleanupExpired();
        PendingMcpInteraction pending = byConfirmationId.get(normalize(confirmationId));
        if (pending == null) {
            throw ErrorCode.BAD_REQUEST.toException("确认任务不存在或已过期，请重新发起问题");
        }
        if (!pending.getUserId().equals(userId) || !pending.getSessionId().equals(sessionId)) {
            throw ErrorCode.FORBIDDEN.toException("确认任务不属于当前用户或会话");
        }

        String normalizedOptionId = normalize(selectedOptionId);
        boolean hasOption = !normalizedOptionId.isBlank();
        boolean hasCustom = customAnswer != null && !customAnswer.isBlank();
        if (hasOption == hasCustom) {
            throw ErrorCode.BAD_REQUEST.toException("请选择一个候选，或填写一个自定义回答，二者不能同时提交");
        }
        if (hasOption && pending.getOptions().stream().noneMatch(option -> option.getId().equals(normalizedOptionId))) {
            throw ErrorCode.BAD_REQUEST.toException("所选候选不属于当前确认任务");
        }
        remove(pending);
        return pending;
    }

    public synchronized void cancel(String userId, String sessionId) {
        cleanupExpired();
        String confirmationId = bySessionKey.remove(sessionKey(userId, sessionId));
        if (confirmationId != null) {
            byConfirmationId.remove(confirmationId);
        }
    }

    public synchronized AiChatInteraction toDto(PendingMcpInteraction pending) {
        AiChatInteraction interaction = new AiChatInteraction();
        interaction.setConfirmationId(pending.getConfirmationId());
        interaction.setQuestion(pending.getQuestion());
        interaction.setAllowCustomAnswer(pending.isAllowCustomAnswer());
        interaction.setExpiresAt(pending.getExpiresAt());
        interaction.setOptions(pending.getOptions().stream()
            .map(option -> new AiChatInteractionOption(
                option.getId(), option.getLabel(), option.getDescription()))
            .toList());
        return interaction;
    }

    private List<ExternalMcpAgentOption> normalizeOptions(List<ExternalMcpAgentOption> options) {
        List<ExternalMcpAgentOption> normalized = new ArrayList<>();
        List<ExternalMcpAgentOption> source = options == null ? List.of() : options;
        int index = 1;
        for (ExternalMcpAgentOption option : source) {
            if (option == null || normalize(option.getLabel()).isBlank()) {
                continue;
            }
            String id = normalize(option.getId());
            if (id.isBlank()) {
                id = "option-" + index;
            }
            normalized.add(new ExternalMcpAgentOption(
                id,
                option.getLabel(),
                option.getDescription(),
                option.getValue() == null ? Map.of() : new LinkedHashMap<>(option.getValue())));
            index++;
            if (normalized.size() >= 3) {
                break;
            }
        }
        return normalized;
    }

    private void cleanupExpired() {
        OffsetDateTime now = OffsetDateTime.now();
        List<PendingMcpInteraction> expired = byConfirmationId.values().stream()
            .filter(pending -> pending.isExpired(now))
            .toList();
        expired.forEach(this::remove);
    }

    private void remove(PendingMcpInteraction pending) {
        byConfirmationId.remove(pending.getConfirmationId());
        bySessionKey.remove(sessionKey(pending.getUserId(), pending.getSessionId()));
    }

    private String sessionKey(String userId, String sessionId) {
        return normalize(userId) + "::" + normalize(sessionId);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
