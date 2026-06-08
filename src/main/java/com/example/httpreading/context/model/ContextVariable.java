package com.example.httpreading.context.model;



/**
**职责**: 表示一个上下文变量  
**应该包含**:
- `key`: 变量名
- `value`: 变量值
- `type`: 数据类型（STRING, NUMBER, OBJECT, ARRAY）
- `importance`: 重要度（0.0-1.0）
- `source`: 来源
- `timestamp`: 创建时间
 */

public class ContextVariable {
    private String key;
    private Object value;
    private String type;
    private Double importance;
    private String source;
    private Long timestamp;

    public ContextVariable() {
    }

    public ContextVariable(
        String key,
        Object value,
        String type,
        Double importance,
        String source,
        Long timestamp
    ) {
        this.key = key;
        this.value = value;
        this.type = type;
        this.importance = importance;
        this.source = source;
        this.timestamp = timestamp;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Double getImportance() {
        return importance;
    }

    public void setImportance(Double importance) {
        this.importance = importance;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }
}