package com.example.httpreading.ai;

import java.util.List;
import java.util.Map;

import com.example.httpreading.service.ai.CollectedEvidence;
import com.example.httpreading.service.ai.EvidenceItem;

final class TestEvidenceFactory {
    private TestEvidenceFactory() {
    }

    static CollectedEvidence from(AiCaseSpec.MockEvidence spec) {
        if (spec == null) {
            return new CollectedEvidence(List.of(), List.of(), List.of(), List.of(), List.of(), "");
        }
        return new CollectedEvidence(
            evidenceItems(spec.items),
            safeList(spec.sources),
            safeList(spec.memoryRefs),
            safeList(spec.externalMcpRefs),
            safeList(spec.externalMcpPlanRefs),
            spec.formattedEvidence);
    }

    private static List<EvidenceItem> evidenceItems(List<Map<String, Object>> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
            .map(TestEvidenceFactory::evidenceItem)
            .toList();
    }

    private static EvidenceItem evidenceItem(Map<String, Object> data) {
        return new EvidenceItem(
            stringValue(data, "id"),
            stringValue(data, "type"),
            stringValue(data, "source"),
            stringValue(data, "content"),
            intValue(data, "priority", 10),
            doubleValue(data, "relevance", 1.0d),
            Map.of());
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static String stringValue(Map<String, Object> data, String key) {
        Object value = data == null ? null : data.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static int intValue(Map<String, Object> data, String key, int defaultValue) {
        Object value = data == null ? null : data.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return defaultValue;
    }

    private static double doubleValue(Map<String, Object> data, String key, double defaultValue) {
        Object value = data == null ? null : data.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return defaultValue;
    }
}
