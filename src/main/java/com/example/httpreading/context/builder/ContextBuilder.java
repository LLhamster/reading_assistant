package com.example.httpreading.context.builder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

@Component
public class ContextBuilder {
    private final ContextBuildConfig config;

    public ContextBuilder() {
        this.config = new ContextBuildConfig();
    }

    public String build(String userQuery,
                        String systemInstructions,
                        List<ContextPacket> packets) {
        List<ContextPacket> gathered = new ArrayList<>();
        if (systemInstructions != null && !systemInstructions.isBlank()) {
            gathered.add(new ContextPacket(systemInstructions, Map.of("type", "instructions")));
        }
        if (packets != null) {
            gathered.addAll(packets);
        }

        List<ContextPacket> selected = select(gathered, userQuery);
        String structured = structure(selected, userQuery);
        return compress(structured);
    }

    private List<ContextPacket> select(List<ContextPacket> packets, String userQuery) {
        for (ContextPacket packet : packets) {
            packet.setRelevanceScore(relevance(userQuery, packet.getContent()));
        }

        List<ContextPacket> instructions = packets.stream()
            .filter(packet -> "instructions".equals(packet.type()))
            .toList();

        List<ContextPacket> remaining = packets.stream()
            .filter(packet -> !"instructions".equals(packet.type()))
            .filter(packet -> packet.getRelevanceScore() >= config.getMinRelevance()
                || Set.of("history", "task_state", "tool_result", "current_chapter").contains(packet.type()))
            .sorted(Comparator.comparingInt(this::typePriority)
                .thenComparing(Comparator.comparingDouble(this::combinedScore).reversed()))
            .toList();

        int budget = config.availableTokens();
        int used = 0;
        List<ContextPacket> selected = new ArrayList<>();

        for (ContextPacket packet : instructions) {
            if (used + packet.getTokenCount() <= budget) {
                selected.add(packet);
                used += packet.getTokenCount();
            }
        }
        for (ContextPacket packet : remaining) {
            if (used + packet.getTokenCount() <= budget) {
                selected.add(packet);
                used += packet.getTokenCount();
            }
        }
        return selected;
    }

    private String structure(List<ContextPacket> packets, String userQuery) {
        List<String> sections = new ArrayList<>();

        appendSection(sections, "Role & Policies", packets, "instructions");
        sections.add("[Task]\n用户问题：" + (userQuery == null ? "" : userQuery));
        appendSection(sections, "State", packets, "task_state");
        appendMultiTypeSection(sections, "Evidence", packets, Set.of("current_chapter", "memory", "knowledge_base", "retrieval", "tool_result"));
        appendSection(sections, "Context", packets, "history");
        sections.add("[Output]\n请基于证据回答。若证据不足，请明确说明；回答末尾列出关键来源。");

        return String.join("\n\n", sections);
    }

    private void appendSection(List<String> sections, String title, List<ContextPacket> packets, String type) {
        List<String> contents = packets.stream()
            .filter(packet -> type.equals(packet.type()))
            .map(ContextPacket::getContent)
            .filter(content -> content != null && !content.isBlank())
            .toList();
        if (!contents.isEmpty()) {
            sections.add("[" + title + "]\n" + String.join("\n", contents));
        }
    }

    private void appendMultiTypeSection(List<String> sections, String title, List<ContextPacket> packets, Set<String> types) {
        List<String> contents = packets.stream()
            .filter(packet -> types.contains(packet.type()))
            .map(ContextPacket::getContent)
            .filter(content -> content != null && !content.isBlank())
            .collect(Collectors.toList());
        if (!contents.isEmpty()) {
            sections.add("[" + title + "]\n" + String.join("\n\n", contents));
        }
    }

    private String compress(String context) {
        if (!config.isCompressionEnabled() || ContextTokenCounter.countTokens(context) <= config.availableTokens()) {
            return context;
        }

        StringBuilder compressed = new StringBuilder();
        int used = 0;
        for (String line : context.split("\\R")) {
            int tokens = ContextTokenCounter.countTokens(line);
            if (used + tokens > config.availableTokens()) {
                break;
            }
            compressed.append(line).append("\n");
            used += tokens;
        }
        return compressed.toString().trim();
    }

    private double combinedScore(ContextPacket packet) {
        long seconds = Math.max(0L, Duration.between(packet.getTimestamp(), LocalDateTime.now()).getSeconds());
        double recency = Math.exp(-(double) seconds / 3600.0d);
        return packet.getRelevanceScore() * 0.7d + recency * 0.3d;
    }

    private int typePriority(ContextPacket packet) {
        return switch (packet.type()) {
            case "tool_result" -> 0;
            case "task_state" -> 1;
            case "knowledge_base", "retrieval" -> 2;
            case "current_chapter" -> 3;
            case "memory" -> 4;
            case "history" -> 5;
            default -> 6;
        };
    }

    private double relevance(String query, String content) {
        if (query == null || query.isBlank() || content == null || content.isBlank()) {
            return 0.0d;
        }
        String normalizedQuery = query.toLowerCase();
        String normalizedContent = content.toLowerCase();
        if (normalizedContent.contains(normalizedQuery)) {
            return 1.0d;
        }

        Set<String> queryTerms = terms(normalizedQuery);
        Set<String> contentTerms = terms(normalizedContent);
        Set<String> intersection = new HashSet<>(queryTerms);
        intersection.retainAll(contentTerms);
        double termScore = queryTerms.isEmpty() ? 0.0d : (double) intersection.size() / queryTerms.size();

        Set<Integer> queryChars = chars(normalizedQuery);
        Set<Integer> contentChars = chars(normalizedContent);
        Set<Integer> charIntersection = new HashSet<>(queryChars);
        charIntersection.retainAll(contentChars);
        double charScore = queryChars.isEmpty() ? 0.0d : (double) charIntersection.size() / queryChars.size();

        return Math.max(termScore, charScore * 0.6d);
    }

    private Set<String> terms(String text) {
        Set<String> result = new HashSet<>();
        for (String token : text.split("[\\s,，。.!！?？;；:：()（）\\[\\]{}<>《》\"']+")) {
            if (!token.isBlank()) {
                result.add(token);
            }
        }
        return result;
    }

    private Set<Integer> chars(String text) {
        Set<Integer> result = new HashSet<>();
        text.codePoints()
            .filter(ch -> !Character.isWhitespace(ch) && !Character.isISOControl(ch))
            .forEach(result::add);
        return result;
    }
}
