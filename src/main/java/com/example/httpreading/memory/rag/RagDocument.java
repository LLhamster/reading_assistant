package com.example.httpreading.memory.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

public class RagDocument {
    private final String content;
    private final Map<String, Object> metadata;
    private final String docId;

    public RagDocument(String content, Map<String, Object> metadata) {
        this.content = content == null ? "" : content;
        this.metadata = metadata == null ? new HashMap<>() : new HashMap<>(metadata);
        this.docId = hash(this.content);
    }

    public String getContent() {
        return content;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public String getDocId() {
        return docId;
    }

    static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception exception) {
            return Integer.toHexString((value == null ? "" : value).hashCode());
        }
    }
}
