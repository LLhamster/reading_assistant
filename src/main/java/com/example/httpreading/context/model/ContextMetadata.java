package com.example.httpreading.context.model;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;



/**
- `source`: 来源（USER_INPUT, TOOL_OUTPUT, LLM_OUTPUT）
- `priority`: 优先级（1-10）
- `ttl`: 生存时间（毫秒）
- `tags`: 标签集合（用于分类）
- `relatedContextIds`: 相关上下文 ID 列表
 */

public class ContextMetadata {
    private String source;
    private Integer priority;
    private Long ttl;
    private Set<String> tags;
    private List<Integer> relatedContextIds;

    public ContextMetadata() {
        this.source = "agent";
        this.priority = 5;
        this.ttl = 7200L;
        this.tags = new HashSet<>();
        this.relatedContextIds = new ArrayList<>();
    }
    
    public ContextMetadata(String source, Integer priority, Long ttl, Set<String> tags, List<Integer> relatedContextIds) {
        this.source = source;
        this.priority = priority;
        this.ttl = ttl;
        this.tags = tags;
        this.relatedContextIds = relatedContextIds;
    }
    public String getSource() {
        return source;
    }
    public void setSource(String source) {
        this.source = source;
    }
    public Integer getPriority() {
        return priority;
    }
    public void setPriority(Integer priority) {
        this.priority = priority;
    }
    public Long getTtl() {
        return ttl;
    }
    public void setTtl(Long ttl) {
        this.ttl = ttl;
    }
    public Set<String> getTags() {
        return tags;
    }
    public void setTags(Set<String> tags) {
        this.tags = tags;
    }
    public List<Integer> getRelatedContextIds() {
        return relatedContextIds;
    }
    public void setRelatedContextIds(List<Integer> relatedContextIds) {
        this.relatedContextIds = relatedContextIds;
    }
}
