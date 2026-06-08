package com.example.httpreading.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.example.httpreading.config.SpringContextHolder;
import com.example.httpreading.memory.MemoryConfig;
import com.example.httpreading.memory.manager.MemoryManager;
import com.example.httpreading.memory.types.WorkingMemory;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = MemoryToolTest.TestConfig.class)
class MemoryToolTest {
	@Configuration
	@Import({SpringContextHolder.class, MemoryManager.class, WorkingMemory.class})
	static class TestConfig {
	}

	@Test
	void addAndSearchWorkingMemory() {
		MemoryTool tool = new MemoryTool(
			"memory",
			"test",
			"user-1",
			new MemoryConfig(new HashMap<>()),
			List.of("working")
		);

		Map<String, Object> addParams = new HashMap<>();
		addParams.put("action", "add");
		addParams.put("content", "Hello memory world");
		addParams.put("memoryType", "working");
		String addResult = tool.run(addParams);
		assertNotNull(addResult);
		assertTrue(addResult.contains("记忆已添加"));
        Map<String, Object> addParams1 = new HashMap<>();
		addParams1.put("action", "add");
		addParams1.put("content", "Hello  chao");
		addParams1.put("memoryType", "working");
		String addResult1 = tool.run(addParams1);

        

		Map<String, Object> searchParams = new HashMap<>();
		searchParams.put("action", "search");
		searchParams.put("query", "Hello");
		searchParams.put("limit", 3);
		searchParams.put("memoryType", "working");
		String searchResult = tool.run(searchParams);

		assertNotNull(searchResult);
		assertFalse(searchResult.isBlank());
		assertTrue(searchResult.contains("找到"));
		assertTrue(searchResult.contains("Hello memory world"));
		System.out.println(searchResult);
	}
}
