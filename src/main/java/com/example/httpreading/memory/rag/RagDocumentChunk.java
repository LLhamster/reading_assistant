package com.example.httpreading.memory.rag;

import java.util.HashMap;
import java.util.Map;

public class RagDocumentChunk {
    private final String content;
    private final Map<String, Object> metadata;
    private final String chunkId;
    private final String docId;
    private final int chunkIndex;

    public RagDocumentChunk(String content,
                            Map<String, Object> metadata,
                            String docId,
                            int chunkIndex) {
        this.content = content == null ? "" : content;
        this.metadata = metadata == null ? new HashMap<>() : new HashMap<>(metadata);
        this.docId = docId;
        this.chunkIndex = chunkIndex;
        this.chunkId = RagDocument.hash(docId + "_" + chunkIndex + "_" + this.content.substring(0, Math.min(50, this.content.length())));
    }

    public String getContent() {
        return content;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public String getChunkId() {
        return chunkId;
    }

    public String getDocId() {
        return docId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }
}
