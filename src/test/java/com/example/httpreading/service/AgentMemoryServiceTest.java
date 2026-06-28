package com.example.httpreading.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.example.httpreading.memory.MemoryConfig;
import com.example.httpreading.memory.manager.MemoryManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class AgentMemoryServiceTest {

    @Test
    void rememberTurnStoresShortSummariesAndMetadata() {
        MemoryManager manager = org.mockito.Mockito.mock(MemoryManager.class);
        ModelClient modelClient = org.mockito.Mockito.mock(ModelClient.class);
        when(manager.addMemory(any(), any(), any(), any())).thenReturn("id");
        when(modelClient.chat(any())).thenReturn("""
            问题：用户想理解长问题的核心
            结论：助手给出了保留语义的短结论
            """);
        AgentMemoryService service = new AgentMemoryService(provider(manager), modelClient);

        service.rememberTurn(
            "u1",
            "s1",
            7L,
            2,
            "这是一个很长的问题".repeat(30),
            "这是一个很长的回答".repeat(80),
            3);

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        verify(manager, org.mockito.Mockito.times(3))
            .addMemory(contentCaptor.capture(), metadataCaptor.capture(), typeCaptor.capture(), any());

        assertEquals("working", typeCaptor.getAllValues().get(0));
        assertEquals("working", typeCaptor.getAllValues().get(1));
        assertEquals("episodic", typeCaptor.getAllValues().get(2));
        assertTrue(contentCaptor.getAllValues().get(0).contains("用户想理解长问题的核心"));
        assertTrue(contentCaptor.getAllValues().get(1).contains("保留语义的短结论"));

        String episodic = contentCaptor.getAllValues().get(2);
        assertTrue(episodic.startsWith("阅读问答摘要"));
        assertTrue(episodic.contains("用户原始提问：这是一个很长的问题"));
        assertTrue(episodic.contains("位置：bookId=7, chapterIndex=2"));
        assertTrue(episodic.length() < 520);

        Map<String, Object> metadata = metadataCaptor.getAllValues().get(2);
        assertEquals("s1", metadata.get("session_id"));
        assertEquals(7L, metadata.get("bookId"));
        assertEquals(2, metadata.get("chapterIndex"));
        assertEquals(3, metadata.get("sourceCount"));
        assertEquals(true, metadata.get("summary"));
        assertTrue(String.valueOf(metadata.get("raw_user_question")).contains("这是一个很长的问题"));
    }

    @Test
    void rememberTurnFallsBackWhenModelSummaryFails() {
        MemoryManager manager = org.mockito.Mockito.mock(MemoryManager.class);
        ModelClient modelClient = org.mockito.Mockito.mock(ModelClient.class);
        when(manager.addMemory(any(), any(), any(), any())).thenReturn("id");
        when(modelClient.chat(any())).thenReturn("模型接口请求失败: 400");
        AgentMemoryService service = new AgentMemoryService(provider(manager), modelClient);

        service.rememberTurn("u1", "s1", 7L, 2, "原始问题".repeat(40), "原始回答".repeat(80), 0);

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(manager, org.mockito.Mockito.times(3))
            .addMemory(contentCaptor.capture(), any(), any(), any());

        assertTrue(contentCaptor.getAllValues().get(0).contains("原始问题"));
        assertTrue(contentCaptor.getAllValues().get(1).contains("原始回答"));
        assertTrue(contentCaptor.getAllValues().get(2).contains("阅读问答摘要"));
    }

    private ObjectProvider<MemoryManager> provider(MemoryManager manager) {
        return new ObjectProvider<>() {
            @Override
            public MemoryManager getObject(Object... args) {
                return manager;
            }

            @Override
            public MemoryManager getIfAvailable() {
                return manager;
            }

            @Override
            public MemoryManager getIfUnique() {
                return manager;
            }

            @Override
            public MemoryManager getObject() {
                return manager;
            }
        };
    }
}
