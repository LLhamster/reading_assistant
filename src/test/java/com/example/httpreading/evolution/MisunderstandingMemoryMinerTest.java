package com.example.httpreading.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.example.httpreading.memory.model.MemoryItem;
import com.example.httpreading.service.AgentMemoryService;
import com.example.httpreading.service.ModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MisunderstandingMemoryMinerTest {
    @Test
    void mergesLlmClassificationWithKeywordFallback() {
        AgentMemoryService memoryService = mock(AgentMemoryService.class);
        ModelClient modelClient = mock(ModelClient.class);
        MemoryItem memory = new MemoryItem(
            "m1", "问题：不要讲概念，直接举例\n结论：定义说明", "episodic", "u1",
            LocalDateTime.now(), 0.8f, Map.of("bookId", 7L, "chapterIndex", 2));
        when(memoryService.recentImportantEpisodic("u1", 20, 0.0)).thenReturn(List.of(memory));
        when(modelClient.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
            {"signals":[{"memoryId":"m1","failureType":"MISSING_EXAMPLE","confidence":0.93}]}
            """);

        List<MisunderstandingSignal> signals = new MisunderstandingMemoryMiner(
            memoryService, modelClient, new ObjectMapper()).mine("u1", 20);

        assertEquals(FailureType.MISSING_EXAMPLE, signals.get(0).failureType());
        assertEquals(7L, signals.get(0).bookId());
        assertEquals(2, signals.get(0).chapterIndex());
    }

    @Test
    void fallsBackWhenModelOutputIsInvalid() {
        AgentMemoryService memoryService = mock(AgentMemoryService.class);
        ModelClient modelClient = mock(ModelClient.class);
        MemoryItem memory = new MemoryItem(
            "m1", "用户说这个回答太简单了", "episodic", "u1",
            LocalDateTime.now(), 0.8f, Map.of());
        when(memoryService.recentImportantEpisodic("u1", 10, 0.0)).thenReturn(List.of(memory));
        when(modelClient.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("not-json");

        List<MisunderstandingSignal> signals = new MisunderstandingMemoryMiner(
            memoryService, modelClient, new ObjectMapper()).mine("u1", 10);

        assertEquals(1, signals.size());
        assertEquals(FailureType.TOO_SIMPLE, signals.get(0).failureType());
    }
}
