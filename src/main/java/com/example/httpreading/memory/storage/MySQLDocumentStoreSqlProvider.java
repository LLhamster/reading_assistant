package com.example.httpreading.memory.storage;

import java.util.Map;

public class MySQLDocumentStoreSqlProvider {
    public String searchMemorySql(Map<String, Object> params) {
        StringBuilder sql = new StringBuilder(
                "SELECT id AS memory_id, user_id, content, memory_type, timestamp, importance, properties, created_at FROM memories");
        boolean hasCondition = false;

        if (hasText(params.get("userId"))) {
            sql.append(hasCondition ? " AND" : " WHERE").append(" user_id = #{userId}");
            hasCondition = true;
        }
        if (hasText(params.get("memoryType"))) {
            sql.append(hasCondition ? " AND" : " WHERE").append(" memory_type = #{memoryType}");
            hasCondition = true;
        }
        if (params.get("startTime") != null) {
            sql.append(hasCondition ? " AND" : " WHERE").append(" timestamp >= #{startTime}");
            hasCondition = true;
        }
        if (params.get("endTime") != null) {
            sql.append(hasCondition ? " AND" : " WHERE").append(" timestamp <= #{endTime}");
            hasCondition = true;
        }
        if (params.get("importanceThreshold") != null) {
            sql.append(hasCondition ? " AND" : " WHERE").append(" importance >= #{importanceThreshold}");
        }

        sql.append(" ORDER BY importance DESC, timestamp DESC LIMIT #{limit}");
        return sql.toString();
    }

    private boolean hasText(Object value) {
        return value instanceof String stringValue && !stringValue.isBlank();
    }
}