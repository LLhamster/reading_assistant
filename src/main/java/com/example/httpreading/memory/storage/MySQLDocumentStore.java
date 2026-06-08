package com.example.httpreading.memory.storage;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MySQLDocumentStore extends DocumentStore {
    private static final Set<String> INITIALIZED_DATABASES = ConcurrentHashMap.newKeySet();
    private static final Gson GSON = new Gson();

    private final DataSource dataSource;
    private final SqlSessionFactory sqlSessionFactory;
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public MySQLDocumentStore() {
        this(null, null, null);
    }

    public MySQLDocumentStore(String jdbcUrl, String username, String password) {
        Properties properties = loadApplicationProperties();
        this.jdbcUrl = resolveOrDefault(jdbcUrl, properties.getProperty("spring.datasource.url"), "jdbc:mysql://localhost:3306/http_reading?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
        this.username = resolveOrDefault(username, properties.getProperty("spring.datasource.username"), "root");
        this.password = resolveOrDefault(password, properties.getProperty("spring.datasource.password"), "");
        this.dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", this.jdbcUrl, this.username, this.password);
        this.sqlSessionFactory = buildSqlSessionFactory();
        initializeDatabaseIfNeeded();
    }

    @Override
    public String addMemory(String memoryId,
                            String userId,
                            String content,
                            String memoryType,
                            long timestamp,
                            double importance,
                            Map<String, Object> properties) {
        String actualMemoryId = memoryId == null || memoryId.isBlank() ? java.util.UUID.randomUUID().toString() : memoryId;
        String actualUserId = userId == null || userId.isBlank() ? "system" : userId;
        Map<String, Object> actualProperties = properties == null ? Map.of() : properties;

        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            MySQLDocumentStoreMapper mapper = session.getMapper(MySQLDocumentStoreMapper.class);
            mapper.upsertUser(actualUserId, actualUserId);
            mapper.upsertMemory(
                actualMemoryId,
                actualUserId,
                content,
                memoryType,
                timestamp,
                importance,
                actualProperties.isEmpty() ? null : GSON.toJson(actualProperties));
            session.commit();
            return actualMemoryId;
        } catch (Exception exception) {
            throw new IllegalStateException("保存 MySQL 记忆失败", exception);
        }
    }

    @Override
    public Map<String, Object> getMemory(String memoryId) {
        if (memoryId == null || memoryId.isBlank()) {
            return null;
        }

        try (SqlSession session = sqlSessionFactory.openSession()) {
            MySQLDocumentStoreMapper mapper = session.getMapper(MySQLDocumentStoreMapper.class);
            Map<String, Object> row = mapper.selectMemoryById(memoryId);
            return row == null ? null : rowToMemoryMap(row);
        } catch (Exception exception) {
            throw new IllegalStateException("读取 MySQL 记忆失败", exception);
        }
    }

    @Override
    public List<Map<String, Object>> searchMemory(String userId,
                                                  String memoryType,
                                                  Long startTime,
                                                  Long endTime,
                                                  Double importanceThreshold,
                                                  int limit) {
        int actualLimit = limit > 0 ? limit : 10;

        try (SqlSession session = sqlSessionFactory.openSession()) {
            MySQLDocumentStoreMapper mapper = session.getMapper(MySQLDocumentStoreMapper.class);
            List<Map<String, Object>> rows = mapper.searchMemory(
                    userId,
                    memoryType,
                    startTime,
                    endTime,
                    importanceThreshold,
                    actualLimit);

            List<Map<String, Object>> memories = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                memories.add(rowToMemoryMap(row));
            }
            return memories;
        } catch (Exception exception) {
            throw new IllegalStateException("搜索 MySQL 记忆失败", exception);
        }
    }

    @Override
    public boolean removeMemory(String memoryId) {
        if (memoryId == null || memoryId.isBlank()) {
            return false;
        }

        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            MySQLDocumentStoreMapper mapper = session.getMapper(MySQLDocumentStoreMapper.class);
            int affected = mapper.deleteMemory(memoryId);
            session.commit();
            return affected > 0;
        } catch (Exception exception) {
            throw new IllegalStateException("删除 MySQL 记忆失败", exception);
        }
    }

    private Properties loadApplicationProperties() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        return properties == null ? new Properties() : properties;
    }

    private String resolveOrDefault(String preferredValue, String yamlValue, String defaultValue) {
        if (preferredValue != null && !preferredValue.isBlank()) {
            return preferredValue;
        }
        if (yamlValue != null && !yamlValue.isBlank()) {
            return yamlValue;
        }
        return defaultValue;
    }

    private void initializeDatabaseIfNeeded() {
        String normalizedKey = jdbcUrl + "|" + username;
        if (!INITIALIZED_DATABASES.add(normalizedKey)) {
            return;
        }

        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            MySQLDocumentStoreMapper mapper = session.getMapper(MySQLDocumentStoreMapper.class);
            mapper.createUsersTable();
            mapper.createMemoriesTable();
            mapper.createConceptsTable();
            mapper.createMemoryConceptsTable();
            mapper.createConceptRelationshipsTable();
            session.commit();
        } catch (Exception exception) {
            throw new IllegalStateException("初始化 MySQL 数据库失败", exception);
        }
    }

    private SqlSessionFactory buildSqlSessionFactory() {
        Configuration configuration = new Configuration();
        configuration.addMapper(MySQLDocumentStoreMapper.class);
        Environment environment = new Environment("mysql-document-store", new JdbcTransactionFactory(), dataSource);
        configuration.setEnvironment(environment);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private Map<String, Object> rowToMemoryMap(Map<String, Object> row) {
        Map<String, Object> memory = new HashMap<>();
        memory.put("memory_id", row.get("memory_id"));
        memory.put("user_id", row.get("user_id"));
        memory.put("content", row.get("content"));
        memory.put("memory_type", row.get("memory_type"));
        memory.put("timestamp", toLong(row.get("timestamp")));
        memory.put("importance", toDouble(row.get("importance")));
        memory.put("properties", parseProperties(row.get("properties")));
        memory.put("created_at", row.get("created_at"));
        return memory;
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        return Long.parseLong(value.toString());
    }

    private double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0.0D;
        }
        return Double.parseDouble(value.toString());
    }

    private Map<String, Object> parseProperties(Object jsonValue) {
        if (jsonValue == null) {
            return new HashMap<>();
        }
        String json = jsonValue.toString();
        if (json.isBlank()) {
            return new HashMap<>();
        }
        Map<String, Object> parsed = GSON.fromJson(json, new TypeToken<Map<String, Object>>() {
        }.getType());
        return parsed == null ? new HashMap<>() : parsed;
    }
}
