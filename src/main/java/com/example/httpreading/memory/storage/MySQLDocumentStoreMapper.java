package com.example.httpreading.memory.storage;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

import java.util.List;
import java.util.Map;

public interface MySQLDocumentStoreMapper {
    @Insert("INSERT IGNORE INTO users (id, name) VALUES (#{userId}, #{name})")
    int upsertUser(@Param("userId") String userId,
                   @Param("name") String name);

    @Insert("INSERT INTO memories " +
            "(id, user_id, content, memory_type, timestamp, importance, properties, updated_at) " +
            "VALUES (#{memoryId}, #{userId}, #{content}, #{memoryType}, #{timestamp}, #{importance}, #{properties}, CURRENT_TIMESTAMP) " +
            "ON DUPLICATE KEY UPDATE " +
            "user_id = VALUES(user_id), " +
            "content = VALUES(content), " +
            "memory_type = VALUES(memory_type), " +
            "timestamp = VALUES(timestamp), " +
            "importance = VALUES(importance), " +
            "properties = VALUES(properties), " +
            "updated_at = CURRENT_TIMESTAMP")
    int upsertMemory(@Param("memoryId") String memoryId,
                     @Param("userId") String userId,
                     @Param("content") String content,
                     @Param("memoryType") String memoryType,
                     @Param("timestamp") long timestamp,
                     @Param("importance") double importance,
                     @Param("properties") String properties);

    @Select("SELECT id AS memory_id, user_id, content, memory_type, timestamp, importance, properties, created_at " +
            "FROM memories WHERE id = #{memoryId}")
    Map<String, Object> selectMemoryById(@Param("memoryId") String memoryId);

    @SelectProvider(type = MySQLDocumentStoreSqlProvider.class, method = "searchMemorySql")
    List<Map<String, Object>> searchMemory(@Param("userId") String userId,
                                           @Param("memoryType") String memoryType,
                                           @Param("startTime") Long startTime,
                                           @Param("endTime") Long endTime,
                                           @Param("importanceThreshold") Double importanceThreshold,
                                           @Param("limit") int limit);

    @Delete("DELETE FROM memories WHERE id = #{memoryId}")
    int deleteMemory(@Param("memoryId") String memoryId);

    @Update("CREATE TABLE IF NOT EXISTS users (" +
            "id VARCHAR(128) PRIMARY KEY, " +
            "name VARCHAR(255), " +
            "properties JSON NULL, " +
            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)")
    int createUsersTable();

    @Update("CREATE TABLE IF NOT EXISTS memories (" +
            "id VARCHAR(128) PRIMARY KEY, " +
            "user_id VARCHAR(128) NOT NULL, " +
            "content TEXT NOT NULL, " +
            "memory_type VARCHAR(64) NOT NULL, " +
            "timestamp BIGINT NOT NULL, " +
            "importance DOUBLE NOT NULL, " +
            "properties JSON NULL, " +
            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
            "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
            "INDEX idx_memories_user_id (user_id), " +
            "INDEX idx_memories_type (memory_type), " +
            "INDEX idx_memories_timestamp (timestamp), " +
            "INDEX idx_memories_importance (importance), " +
            "CONSTRAINT fk_memories_user FOREIGN KEY (user_id) REFERENCES users(id))")
    int createMemoriesTable();

    @Update("CREATE TABLE IF NOT EXISTS concepts (" +
            "id VARCHAR(128) PRIMARY KEY, " +
            "name VARCHAR(255) NOT NULL, " +
            "description TEXT, " +
            "properties JSON NULL, " +
            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)")
    int createConceptsTable();

    @Update("CREATE TABLE IF NOT EXISTS memory_concepts (" +
            "memory_id VARCHAR(128) NOT NULL, " +
            "concept_id VARCHAR(128) NOT NULL, " +
            "relevance_score DOUBLE DEFAULT 1.0, " +
            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
            "PRIMARY KEY (memory_id, concept_id), " +
            "INDEX idx_memory_concepts_memory (memory_id), " +
            "INDEX idx_memory_concepts_concept (concept_id), " +
            "CONSTRAINT fk_memory_concepts_memory FOREIGN KEY (memory_id) REFERENCES memories(id) ON DELETE CASCADE, " +
            "CONSTRAINT fk_memory_concepts_concept FOREIGN KEY (concept_id) REFERENCES concepts(id) ON DELETE CASCADE)")
    int createMemoryConceptsTable();

    @Update("CREATE TABLE IF NOT EXISTS concept_relationships (" +
            "from_concept_id VARCHAR(128) NOT NULL, " +
            "to_concept_id VARCHAR(128) NOT NULL, " +
            "relationship_type VARCHAR(128) NOT NULL, " +
            "strength DOUBLE DEFAULT 1.0, " +
            "properties JSON NULL, " +
            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
            "PRIMARY KEY (from_concept_id, to_concept_id, relationship_type), " +
            "CONSTRAINT fk_relationship_from FOREIGN KEY (from_concept_id) REFERENCES concepts(id) ON DELETE CASCADE, " +
            "CONSTRAINT fk_relationship_to FOREIGN KEY (to_concept_id) REFERENCES concepts(id) ON DELETE CASCADE)")
    int createConceptRelationshipsTable();
}
