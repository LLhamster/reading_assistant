package com.example.httpreading.context.model;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 职责: 表示一个上下文实例
 * 应该包含:
 * - `contextId`: 上下文 ID
 * - `metadata`: 上下文变量的键值对：userInput、systemPrompt、toolResult、currentStep等
 * - `contextWindow`: 上下文历史窗口：保存曾经的对话内容
 * - `contextMetadata`: 上下文的元信息：来源、优先级、TTL、标签等，用于上下文管理和检索（当多用户以及多会话发生时）
 */

public class Context {
    private Integer contextId;
    private Map<String, ContextVariable> metadata;
    private ContextWindow contextWindow;
    private ContextMetadata contextMetadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    // private String version;
    public Context(){
        this.metadata = new HashMap<>();
        this.contextWindow = new ContextWindow();
        this.contextMetadata = new ContextMetadata();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Context(Integer contextId, Map<String, ContextVariable> metadata, ContextWindow contextWindow, ContextMetadata contextMetadata) {
        this.contextId = contextId;
        this.metadata = metadata;
        this.contextWindow = contextWindow;
        this.contextMetadata = contextMetadata;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
    public Integer getContextId() {
        return contextId;
    }
    public void setContextId(Integer contextId) {
        this.contextId = contextId;
    }
    public Map<String, ContextVariable> getMetadata() {
        return metadata;
    }
    public void setMetadata(Map<String, ContextVariable> metadata) {
        this.metadata = metadata;   
    }
    public ContextWindow getContextWindow() {
        return contextWindow;
    }
    public void setContextWindow(ContextWindow contextWindow) {
        this.contextWindow = contextWindow;
    }
    public ContextMetadata getContextMetadata() {
        return contextMetadata;
    }
    public void setContextMetadata(ContextMetadata contextMetadata) {
        this.contextMetadata = contextMetadata;
    }
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

}
