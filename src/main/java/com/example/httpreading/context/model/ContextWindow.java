package com.example.httpreading.context.model;

import java.util.LinkedList;

/**
**职责**: 管理上下文历史窗口  
**应该包含**:
- `maxSize`: 最大窗口大小（条数）
- `maxTokens`: 最大 token 数（用于 LLM）
- `history`: LinkedList<ContextSnapshot> 历史记录
- `currentIndex`: 当前位置
 */
public class ContextWindow {
    private Integer maxSize;
    private Integer maxTokens;
    private LinkedList<ContextSnapshot> history;    
    private Integer currentIndex;

    public ContextWindow() {
        this.maxSize = 20;
        this.maxTokens = 4000;
        this.history = new LinkedList<>();
        this.currentIndex = -1;
    }

    public ContextWindow(
        Integer maxSize,
        Integer maxTokens,
        LinkedList<ContextSnapshot> history,
        Integer currentIndex
    ) {
        this.maxSize = maxSize;
        this.maxTokens = maxTokens;
        this.history = history;
        this.currentIndex = currentIndex;
    }

    public Integer getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(Integer maxSize) {
        this.maxSize = maxSize;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public LinkedList<ContextSnapshot> getHistory() {
        return history;
    }

    public void setHistory(LinkedList<ContextSnapshot> history) {
        this.history = history;
    }

    public Integer getCurrentIndex() {
        return currentIndex;
    }

    public void setCurrentIndex(Integer currentIndex) {
        this.currentIndex = currentIndex;
    }
}
