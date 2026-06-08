package com.example.httpreading.memory.rag;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RagDocumentProcessor {
    private final int chunkSize;
    private final int chunkOverlap;
    private final List<String> separators;

    public RagDocumentProcessor(int chunkSize, int chunkOverlap) {
        this(chunkSize, chunkOverlap, List.of("\n\n", "\n", "。", ".", " "));
    }

    public RagDocumentProcessor(int chunkSize, int chunkOverlap, List<String> separators) {
        this.chunkSize = Math.max(1, chunkSize);
        this.chunkOverlap = Math.max(0, Math.min(chunkOverlap, this.chunkSize - 1));
        this.separators = separators == null || separators.isEmpty()
            ? List.of("\n\n", "\n", "。", ".", " ")
            : separators;
    }

    public List<RagDocumentChunk> processDocument(RagDocument document) {
        List<String> pieces = splitText(document.getContent());
        List<RagDocumentChunk> chunks = new ArrayList<>();
        for (int i = 0; i < pieces.size(); i++) {
            Map<String, Object> metadata = new java.util.HashMap<>(document.getMetadata());
            metadata.put("doc_id", document.getDocId());
            metadata.put("chunk_index", i);
            metadata.put("total_chunks", pieces.size());
            metadata.put("processed_at", Instant.now().toString());
            chunks.add(new RagDocumentChunk(pieces.get(i), metadata, document.getDocId(), i));
        }
        return chunks;
    }

    public List<RagDocumentChunk> processDocuments(List<RagDocument> documents) {
        List<RagDocumentChunk> chunks = new ArrayList<>();
        if (documents == null) {
            return chunks;
        }
        for (RagDocument document : documents) {
            chunks.addAll(processDocument(document));
        }
        return chunks;
    }

    private List<String> splitText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (text.length() <= chunkSize) {
            return List.of(text);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            if (end >= text.length()) {
                chunks.add(text.substring(start));
                break;
            }

            int splitPoint = findSplitPoint(text, start, end);
            if (splitPoint <= start) {
                splitPoint = end;
            }
            chunks.add(text.substring(start, splitPoint));
            start = Math.max(start + 1, splitPoint - chunkOverlap);
        }
        return chunks;
    }

    private int findSplitPoint(String text, int start, int end) {
        int searchStart = Math.max(start, end - 100);
        for (String separator : separators) {
            for (int i = end - separator.length(); i >= searchStart; i--) {
                if (text.startsWith(separator, i)) {
                    return i + separator.length();
                }
            }
        }
        return -1;
    }
}
