package com.example.httpreading.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.httpreading.memory.embedding.model.EmbeddingModel;
import com.example.httpreading.memory.model.MemoryItem;
import com.example.httpreading.memory.storage.DocumentStore;
import com.example.httpreading.memory.storage.QdrantStore;
import com.example.httpreading.memory.types.EpisodicMemory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class EpisodicMemoryPersistenceTest {

    @Test
    void retrievesDurableMemoryWhenVectorIndexIsEmpty() {
        DocumentStore docStore = mock(DocumentStore.class);
        QdrantStore qdrantStore = mock(QdrantStore.class);
        EmbeddingModel embedder = mock(EmbeddingModel.class);
        EpisodicMemory memory = memory(docStore, qdrantStore, embedder);

        Map<String, Object> properties = new HashMap<>();
        properties.put("session_id", "session-1");
        Map<String, Object> durableDoc = new HashMap<>();
        durableDoc.put("memory_id", "memory-1");
        durableDoc.put("user_id", "user-1");
        durableDoc.put("memory_type", "episodic");
        durableDoc.put("content", "用户之前询问了机会主义");
        durableDoc.put("timestamp", 1_700_000_000L);
        durableDoc.put("importance", 0.8d);
        durableDoc.put("properties", properties);

        when(embedder.encode(anyString())).thenReturn(new double[] {1d, 0d});
        when(qdrantStore.searchSimilar(any(), anyInt(), any(), any())).thenReturn(List.of());
        when(docStore.searchMemory(
            eq("user-1"), eq("episodic"), any(), any(), any(), anyInt()))
            .thenReturn(List.of(durableDoc));

        List<MemoryItem> result = memory.retrieve(
            "机会主义",
            5,
            Map.of("userId", "user-1"));

        assertEquals(1, result.size());
        assertEquals("memory-1", result.get(0).getId());
        assertEquals("用户之前询问了机会主义", result.get(0).getContent());
    }

    @Test
    void doesNotExposeEpisodeWhenDurableWriteFails() {
        DocumentStore docStore = mock(DocumentStore.class);
        EpisodicMemory memory = memory(docStore, null, mock(EmbeddingModel.class));
        when(docStore.addMemory(
            anyString(), anyString(), anyString(), eq("episodic"), anyLong(), anyDouble(), any()))
            .thenThrow(new IllegalStateException("database unavailable"));

        MemoryItem item = new MemoryItem(
            "memory-1",
            "一次需要持久保存的情景",
            "episodic",
            "user-1",
            LocalDateTime.now(),
            0.8f,
            Map.of("session_id", "session-1"));

        assertThrows(IllegalStateException.class, () -> memory.addMemory(item));
        assertEquals(List.of(), memory.getAll());
    }

    private EpisodicMemory memory(DocumentStore docStore,
                                  QdrantStore qdrantStore,
                                  EmbeddingModel embedder) {
        EpisodicMemory memory = new EpisodicMemory(Map.of());
        ReflectionTestUtils.setField(memory, "docStore", docStore);
        ReflectionTestUtils.setField(memory, "qdrantStore", qdrantStore);
        ReflectionTestUtils.setField(memory, "embedder", embedder);
        ReflectionTestUtils.setField(memory, "qdrantVectorSize", 2);
        return memory;
    }
}
