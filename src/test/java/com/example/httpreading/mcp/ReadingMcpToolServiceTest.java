package com.example.httpreading.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.httpreading.context.builder.ContextBuilder;
import com.example.httpreading.context.manager.DefaultContextManager;
import com.example.httpreading.domain.document.ChunkDoc;
import com.example.httpreading.memory.model.MemoryItem;
import com.example.httpreading.service.AgentMemoryService;
import com.example.httpreading.service.RagService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadingMcpToolServiceTest {
    @Mock
    private AgentMemoryService agentMemoryService;
    @Mock
    private RagService ragService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ReadingMcpToolService service;

    @BeforeEach
    void setUp() {
        service = new ReadingMcpToolService(
            agentMemoryService,
            ragService,
            new DefaultContextManager(),
            new ContextBuilder(),
            objectMapper);
    }

    @Test
    void memorySearchReturnsMemoriesAndUsesDefaults() throws Exception {
        MemoryItem memory = new MemoryItem(
            "m1",
            "hello memory",
            "working",
            "default_user",
            LocalDateTime.now(),
            0.8f,
            Map.of("session_id", "default_session"));
        when(agentMemoryService.search("default_user", "default_session", "hello", 5))
            .thenReturn(List.of(memory));

        Map<String, Object> response = json(service.memorySearch(Map.of("query", "hello", "limit", 0)));

        assertEquals(true, response.get("ok"));
        List<?> data = (List<?>) response.get("data");
        assertEquals(1, data.size());
        verify(agentMemoryService).search("default_user", "default_session", "hello", 5);
    }

    @Test
    void memorySearchRejectsBlankQuery() throws Exception {
        Map<String, Object> response = json(service.memorySearch(Map.of("query", " ")));

        assertEquals(false, response.get("ok"));
        assertTrue(response.get("error").toString().contains("query"));
    }

    @Test
    void memoryRememberTurnCallsService() throws Exception {
        Map<String, Object> response = json(service.memoryRememberTurn(Map.of(
            "userId", "u1",
            "sessionId", "s1",
            "bookId", 7,
            "chapterIndex", 2,
            "question", "q",
            "answer", "a")));

        assertEquals(true, response.get("ok"));
        verify(agentMemoryService).rememberTurn("u1", "s1", 7L, 2, "q", "a");
    }

    @Test
    void ragRetrieveReturnsEmptyResultMessage() throws Exception {
        when(ragService.retrieve(null, null, "missing", 3)).thenReturn(List.of());

        Map<String, Object> response = json(service.ragRetrieve(Map.of("query", "missing", "topK", -1)));

        assertEquals(true, response.get("ok"));
        assertEquals("RAG 未检索到相关内容", response.get("message"));
        assertTrue(((List<?>) response.get("data")).isEmpty());
        verify(ragService).retrieve(null, null, "missing", 3);
    }

    @Test
    void ragRetrieveNormalizesNullAndEmptyChunksToEmptyData() throws Exception {
        ChunkDoc emptyContent = chunk("empty-content");
        emptyContent.setContent(null);
        List<ChunkDoc> chunks = new ArrayList<>();
        chunks.add(null);
        chunks.add(emptyContent);
        when(ragService.retrieve(null, null, "bad chunks", 3))
            .thenReturn(chunks);

        Map<String, Object> response = json(service.ragRetrieve(Map.of("query", "bad chunks")));

        assertEquals(true, response.get("ok"));
        assertEquals("RAG 未检索到相关内容", response.get("message"));
        assertTrue(((List<?>) response.get("data")).isEmpty());
    }

    @Test
    void ragRetrieveNormalizesNullRetrieveResultToEmptyData() throws Exception {
        when(ragService.retrieve(null, null, "null result", 3)).thenReturn(null);

        Map<String, Object> response = json(service.ragRetrieve(Map.of("query", "null result")));

        assertEquals(true, response.get("ok"));
        assertEquals("RAG 未检索到相关内容", response.get("message"));
        assertTrue(((List<?>) response.get("data")).isEmpty());
    }

    @Test
    void ragAnswerReturnsAnswerAndSources() throws Exception {
        ChunkDoc chunk = chunk("c1");
        when(ragService.answer(1L, 3, "what", 4, null))
            .thenReturn(new RagService.RagAnswer("answer text", List.of(chunk)));

        Map<String, Object> response = json(service.ragAnswer(Map.of(
            "bookId", 1,
            "chapterIndex", 3,
            "question", "what",
            "topK", 4)));

        assertEquals(true, response.get("ok"));
        Map<?, ?> data = (Map<?, ?>) response.get("data");
        assertEquals("answer text", data.get("answer"));
        assertFalse(((List<?>) data.get("sources")).isEmpty());
    }

    @Test
    void contextBuildReturnsContextTextAndContextId() throws Exception {
        Map<String, Object> response = json(service.contextBuild(Map.of(
            "userId", "u1",
            "sessionId", "s1",
            "question", "当前问题",
            "systemInstructions", "系统指令",
            "packets", List.of(Map.of("type", "tool_result", "content", "工具结果内容")))));

        assertEquals(true, response.get("ok"));
        Map<?, ?> data = (Map<?, ?>) response.get("data");
        assertTrue(data.containsKey("contextId"));
        assertTrue(data.get("context").toString().contains("当前问题"));
        assertTrue(data.get("context").toString().contains("工具结果内容"));
    }

    private ChunkDoc chunk(String id) {
        ChunkDoc chunk = new ChunkDoc();
        chunk.setId(id);
        chunk.setBookId(1L);
        chunk.setChapterIndex(3);
        chunk.setChunkIndex(0);
        chunk.setBookTitle("Book");
        chunk.setChapterTitle("Chapter");
        chunk.setContent("chunk content");
        return chunk;
    }

    private Map<String, Object> json(String value) throws Exception {
        return objectMapper.readValue(value, new TypeReference<>() {
        });
    }
}
