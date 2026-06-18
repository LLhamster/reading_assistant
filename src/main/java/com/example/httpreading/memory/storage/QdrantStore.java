package com.example.httpreading.memory.storage;

import com.google.gson.Gson;
import com.example.httpreading.config.QdrantProperties;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class QdrantStore {
	private static final Logger logger = LoggerFactory.getLogger(QdrantStore.class);
	private static final Gson GSON = new Gson();

	private final String url;
	private final String apiKey;
	private final String collectionName;
	private final int vectorSize;
	private final int timeout;
	private final int hnswM;
	private final int hnswEfConstruct;
	private final int searchEf;
	private final boolean searchExact;
	private final String distance;
	private final WebClient webClient;

	public QdrantStore(QdrantProperties properties, WebClient webClient) {
		String resolvedCollection = properties == null ? null : properties.getCollection();
		int resolvedVectorSize = properties == null ? 0 : properties.getVectorSize();
		int resolvedTimeout = properties == null ? 0 : properties.getTimeout();

		this.url = properties == null ? null : properties.getUrl();
		this.apiKey = properties == null ? null : properties.getApiKey();
		this.collectionName = resolvedCollection == null || resolvedCollection.isBlank()
				? "hello_agents_vectors"
				: resolvedCollection;
		this.vectorSize = resolvedVectorSize <= 0 ? 384 : resolvedVectorSize;
		this.timeout = resolvedTimeout <= 0 ? 30 : resolvedTimeout;
		this.hnswM = parseEnvInt("QDRANT_HNSW_M", 32);
		this.hnswEfConstruct = parseEnvInt("QDRANT_HNSW_EF_CONSTRUCT", 256);
		this.searchEf = parseEnvInt("QDRANT_SEARCH_EF", 128);
		this.searchExact = "1".equals(System.getenv().getOrDefault("QDRANT_SEARCH_EXACT", "0"));
		this.distance = mapDistance(properties == null ? null : properties.getDistance());
		this.webClient = webClient == null ? buildWebClient() : webClient;

		initializeClient();
	}

	private WebClient buildWebClient() {
		String base = isBlank(url) ? "http://localhost:6333" : url;
		WebClient.Builder builder = WebClient.builder().baseUrl(base)
				.defaultHeader("Content-Type", "application/json");
		if (!isBlank(apiKey)) {
			builder.defaultHeader("api-key", apiKey);
		}
		// Configure timeouts via Reactor Netty client
		try {
			reactor.netty.http.client.HttpClient httpClient = reactor.netty.http.client.HttpClient.create()
					.responseTimeout(Duration.ofSeconds(timeout))
					.option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout * 1000);
			builder.clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient));
		} catch (Throwable ignored) {
			// fallback to default if Reactor Netty not available
		}
		return builder.build();
	}


	private void initializeClient() {
		try {
			if (isBlank(url)) {
				logger.info("成功连接到本地Qdrant服务: localhost:6333");
			} else if (isBlank(apiKey)) {
				logger.info("成功连接到Qdrant服务: {}", url);
			} else {
				logger.info("成功连接到Qdrant云服务: {}", url);
			}

			getCollections();
			ensureCollection();
		} catch (Exception e) {
			logger.error("Qdrant连接失败: {}", e.getMessage());
			if (isBlank(url)) {
				logger.info("本地连接失败，可以考虑使用Qdrant云服务");
				logger.info("或启动本地服务: docker run -p 6333:6333 qdrant/qdrant");
			} else {
				logger.info("请检查URL和API密钥是否正确");
			}
			throw new IllegalStateException("Qdrant初始化失败", e);
		}
	}

	private void ensureCollection() {
		try {
			JSONArray collections = getCollections();
			boolean exists = false;
			for (int i = 0; i < collections.length(); i++) {
				JSONObject c = collections.optJSONObject(i);
				if (c != null && collectionName.equals(c.optString("name"))) {
					exists = true;
					break;
				}
			}

			if (!exists) {
				JSONObject body = new JSONObject()
						.put("vectors", new JSONObject()
								.put("size", vectorSize)
								.put("distance", distance))
						.put("hnsw_config", new JSONObject()
								.put("m", hnswM)
								.put("ef_construct", hnswEfConstruct));
				requestJson("PUT", "/collections/" + collectionName, body);
				logger.info("创建Qdrant集合: {}", collectionName);
			} else {
				logger.info("使用现有Qdrant集合: {}", collectionName);
				try {
					JSONObject body = new JSONObject()
							.put("hnsw_config", new JSONObject()
									.put("m", hnswM)
									.put("ef_construct", hnswEfConstruct));
					requestJson("PATCH", "/collections/" + collectionName, body);
				} catch (Exception ignore) {
					logger.debug("跳过更新HNSW配置: {}", ignore.getMessage());
				}
			}

			ensurePayloadIndexes();
		} catch (Exception e) {
			logger.error("集合初始化失败: {}", e.getMessage());
			throw new IllegalStateException("集合初始化失败", e);
		}
	}

	private void ensurePayloadIndexes() {
		List<Map.Entry<String, String>> indexFields = List.of(
				Map.entry("memory_type", "keyword"),
				Map.entry("user_id", "keyword"),
				Map.entry("memory_id", "keyword"),
				Map.entry("timestamp", "integer"),
				Map.entry("modality", "keyword"),
				Map.entry("source", "keyword"),
				Map.entry("external", "bool"),
				Map.entry("namespace", "keyword"),
				Map.entry("is_rag_data", "bool"),
				Map.entry("rag_namespace", "keyword"),
				Map.entry("data_source", "keyword")
		);

		for (Map.Entry<String, String> field : indexFields) {
			try {
				JSONObject body = new JSONObject()
						.put("field_name", field.getKey())
						.put("field_schema", field.getValue());
				requestJson("PUT", "/collections/" + collectionName + "/index", body);
			} catch (Exception e) {
				logger.debug("索引 {} 已存在或创建失败: {}", field.getKey(), e.getMessage());
			}
		}
	}

	public boolean addVectors(List<List<Double>> vectors,
							  List<Map<String, Object>> metadata,
							  List<String> ids) {
		try {
			if (vectors == null || vectors.isEmpty()) {
				logger.warn("向量列表为空");
				return false;
			}

			List<String> actualIds = ids;
			if (actualIds == null) {
				actualIds = new ArrayList<>();
				for (int i = 0; i < vectors.size(); i++) {
					actualIds.add("vec_" + i + "_" + (Instant.now().toEpochMilli() * 1000));
				}
			}

			logger.info("[Qdrant] add_vectors start: n_vectors={} n_meta={} collection={}",
					vectors.size(), metadata == null ? 0 : metadata.size(), collectionName);

			JSONArray points = new JSONArray();
			List<Map<String, Object>> metadataList = metadata == null ? List.of() : metadata;
			int n = Math.min(vectors.size(), Math.min(metadataList.size(), actualIds.size()));
			for (int i = 0; i < n; i++) {
				List<Double> vector = vectors.get(i);
				Map<String, Object> rawMeta = metadataList.get(i);
				Map<String, Object> meta = rawMeta == null ? new HashMap<>() : new HashMap<>(rawMeta);
				String pointId = actualIds.get(i);

				if (vector == null) {
					logger.error("[Qdrant] 非法向量类型: index={} type=null", i);
					continue;
				}
				if (vector.size() != vectorSize) {
					logger.warn("向量维度不匹配: 期望{}, 实际{}", vectorSize, vector.size());
					continue;
				}

				int nowTs = (int) Instant.now().getEpochSecond();
				meta.put("timestamp", nowTs);
				meta.put("added_at", nowTs);
				normalizeExternal(meta);

				Object safeId = toSafeQdrantId(pointId);
				JSONObject point = new JSONObject()
						.put("id", safeId)
						.put("vector", new JSONArray(vector))
						.put("payload", new JSONObject(meta));
				points.put(point);
			}

			if (points.isEmpty()) {
				logger.warn("没有有效的向量点");
				return false;
			}

			logger.info("[Qdrant] upsert begin: points={}", points.length());
			JSONObject body = new JSONObject().put("points", points);
			requestJson("PUT", "/collections/" + collectionName + "/points?wait=true", body);
			logger.info("[Qdrant] upsert done");
			logger.info("成功添加 {} 个向量到Qdrant", points.length());
			return true;
		} catch (Exception e) {
			logger.error("添加向量失败: {}", e.getMessage());
			return false;
		}
	}

	public List<Map<String, Object>> searchSimilar(List<Double> queryVector,
												   int limit,
												   Double scoreThreshold,
												   Map<String, Object> where) {
		try {
			if (queryVector == null || queryVector.size() != vectorSize) {
				logger.error("查询向量维度错误: 期望{}, 实际{}", vectorSize, queryVector == null ? 0 : queryVector.size());
				return List.of();
			}

			JSONObject body = new JSONObject()
					.put("vector", new JSONArray(queryVector))
					.put("limit", limit <= 0 ? 10 : limit)
					.put("with_payload", true)
					.put("with_vector", false)
					.put("params", new JSONObject()
							.put("hnsw_ef", searchEf)
							.put("exact", searchExact));

			if (scoreThreshold != null) {
				body.put("score_threshold", scoreThreshold);
			}

			JSONObject queryFilter = buildFilter(where);
			if (queryFilter != null) {
				body.put("filter", queryFilter);
			}

			JSONObject response = requestJson("POST", "/collections/" + collectionName + "/points/search", body);
			JSONArray resultsArray = response.optJSONArray("result");
			if (resultsArray == null) {
				return List.of();
			}

			List<Map<String, Object>> results = new ArrayList<>();
			for (int i = 0; i < resultsArray.length(); i++) {
				JSONObject hit = resultsArray.optJSONObject(i);
				if (hit == null) {
					continue;
				}
				Map<String, Object> result = new HashMap<>();
				result.put("id", hit.opt("id"));
				result.put("score", hit.optDouble("score"));
				JSONObject payload = hit.optJSONObject("payload");
				result.put("metadata", payload == null ? new HashMap<String, Object>() : jsonObjectToMap(payload));
				results.add(result);
			}
			logger.debug("Qdrant搜索返回 {} 个结果", results.size());
			return results;
		} catch (Exception e) {
			logger.error("向量搜索失败: {}", e.getMessage());
			return List.of();
		}
	}

	public boolean deleteVectors(List<String> ids) {
		try {
			if (ids == null || ids.isEmpty()) {
				return true;
			}

			JSONObject body = new JSONObject().put("points", new JSONArray(ids));
			requestJson("POST", "/collections/" + collectionName + "/points/delete?wait=true", body);
			logger.info("成功删除 {} 个向量", ids.size());
			return true;
		} catch (Exception e) {
			logger.error("删除向量失败: {}", e.getMessage());
			return false;
		}
	}

	public boolean clearCollection() {
		try {
			requestNoContent("DELETE", "/collections/" + collectionName);
			ensureCollection();
			logger.info("成功清空Qdrant集合: {}", collectionName);
			return true;
		} catch (Exception e) {
			logger.error("清空集合失败: {}", e.getMessage());
			return false;
		}
	}

	public void deleteMemories(List<String> memoryIds) {
		try {
			if (memoryIds == null || memoryIds.isEmpty()) {
				return;
			}

			JSONArray should = new JSONArray();
			for (String mid : memoryIds) {
				should.put(new JSONObject()
						.put("key", "memory_id")
						.put("match", new JSONObject().put("value", mid)));
			}

			JSONObject body = new JSONObject()
					.put("filter", new JSONObject().put("should", should));

			requestJson("POST", "/collections/" + collectionName + "/points/delete?wait=true", body);
			logger.info("成功按memory_id删除 {} 个Qdrant向量", memoryIds.size());
		} catch (Exception e) {
			logger.error("删除记忆失败: {}", e.getMessage());
			throw new IllegalStateException("删除记忆失败", e);
		}
	}

	public Map<String, Object> getCollectionInfo() {
		try {
			JSONObject response = requestJson("GET", "/collections/" + collectionName, null);
			JSONObject result = response.optJSONObject("result");
			if (result == null) {
				return Map.of();
			}

			Map<String, Object> info = new HashMap<>();
			info.put("name", collectionName);
			info.put("vectors_count", optLong(result, "vectors_count"));
			info.put("indexed_vectors_count", optLong(result, "indexed_vectors_count"));
			info.put("points_count", optLong(result, "points_count"));
			info.put("segments_count", optLong(result, "segments_count"));
			info.put("config", Map.of(
					"vector_size", vectorSize,
					"distance", distance
			));
			return info;
		} catch (Exception e) {
			logger.error("获取集合信息失败: {}", e.getMessage());
			return Map.of();
		}
	}

	public Map<String, Object> getCollectionStats() {
		Map<String, Object> info = new HashMap<>(getCollectionInfo());
		if (info.isEmpty()) {
			return Map.of("store_type", "qdrant", "name", collectionName);
		}
		info.put("store_type", "qdrant");
		return info;
	}

	public boolean healthCheck() {
		try {
			getCollections();
			return true;
		} catch (Exception e) {
			logger.error("Qdrant健康检查失败: {}", e.getMessage());
			return false;
		}
	}

	public void close() {
		// WebClient does not require explicit connection pool eviction here.
	}

	private JSONArray getCollections() {
		JSONObject response = requestJson("GET", "/collections", null);
		JSONObject result = response.optJSONObject("result");
		if (result == null) {
			return new JSONArray();
		}
		JSONArray collections = result.optJSONArray("collections");
		return collections == null ? new JSONArray() : collections;
	}

	private JSONObject requestJson(String method, String pathWithQuery, JSONObject body) {
		try {
			String resp = webClient.method(HttpMethod.valueOf(method))
					.uri(pathWithQuery)
					.contentType(MediaType.APPLICATION_JSON)
					.bodyValue(body == null ? "{}" : body.toString())
					.retrieve()
					.bodyToMono(String.class)
					.block();
			String payload = resp == null ? "{}" : resp;
			return payload.isBlank() ? new JSONObject() : new JSONObject(payload);
		} catch (WebClientResponseException e) {
			String errBody = e.getResponseBodyAsString();
			throw new IllegalStateException("Qdrant请求失败: HTTP " + e.getRawStatusCode() + " body=" + errBody, e);
		} catch (Exception e) {
			throw new IllegalStateException("Qdrant请求失败", e);
		}
	}

	private void requestNoContent(String method, String pathWithQuery) {
		try {
			webClient.method(HttpMethod.valueOf(method))
					.uri(pathWithQuery)
					.retrieve()
					.toBodilessEntity()
					.block();
		} catch (WebClientResponseException e) {
			String errBody = e.getResponseBodyAsString();
			throw new IllegalStateException("Qdrant请求失败: HTTP " + e.getRawStatusCode() + " body=" + errBody, e);
		} catch (Exception e) {
			throw new IllegalStateException("Qdrant请求失败", e);
		}
	}

	// buildRequest removed — WebClient is used instead

	private JSONObject buildFilter(Map<String, Object> where) {
		if (where == null || where.isEmpty()) {
			return null;
		}
		JSONArray must = new JSONArray();
		for (Map.Entry<String, Object> entry : where.entrySet()) {
			Object value = entry.getValue();
			if (value instanceof String || value instanceof Number || value instanceof Boolean) {
				must.put(new JSONObject()
						.put("key", entry.getKey())
						.put("match", new JSONObject().put("value", value)));
			}
		}
		return must.isEmpty() ? null : new JSONObject().put("must", must);
	}

	private Object toSafeQdrantId(String pointId) {
		if (pointId == null || pointId.isBlank()) {
			return UUID.randomUUID().toString();
		}
		String trimmed = pointId.trim();
		if (trimmed.matches("\\d+")) {
			try {
				return Long.parseUnsignedLong(trimmed);
			} catch (NumberFormatException ignored) {
				return UUID.nameUUIDFromBytes(trimmed.getBytes(StandardCharsets.UTF_8)).toString();
			}
		}
		try {
			return UUID.fromString(trimmed).toString();
		} catch (IllegalArgumentException ignored) {
			return UUID.nameUUIDFromBytes(trimmed.getBytes(StandardCharsets.UTF_8)).toString();
		}
	}

	private void normalizeExternal(Map<String, Object> payload) {
		if (!payload.containsKey("external")) {
			return;
		}
		Object val = payload.get("external");
		if (val instanceof Boolean) {
			return;
		}
		String normalized = val == null ? "" : val.toString().toLowerCase();
		payload.put("external", "1".equals(normalized) || "true".equals(normalized) || "yes".equals(normalized));
	}

	private long optLong(JSONObject obj, String key) {
		if (!obj.has(key) || obj.isNull(key)) {
			return 0L;
		}
		Object value = obj.get(key);
		if (value instanceof Number number) {
			return number.longValue();
		}
		return Long.parseLong(String.valueOf(value));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> jsonObjectToMap(JSONObject jsonObject) {
		return GSON.fromJson(jsonObject.toString(), Map.class);
	}

	private int parseEnvInt(String key, int fallback) {
		try {
			return Integer.parseInt(System.getenv().getOrDefault(key, String.valueOf(fallback)));
		} catch (Exception ignore) {
			return fallback;
		}
	}

	private String mapDistance(String inputDistance) {
		if (inputDistance == null) {
			return "Cosine";
		}
		return switch (inputDistance.toLowerCase()) {
			case "dot" -> "Dot";
			case "euclidean" -> "Euclid";
			default -> "Cosine";
		};
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	public String getCollectionName() {
		return collectionName;
	}

	public int getVectorSize() {
		return vectorSize;
	}

	public String getDistance() {
		return distance;
	}

	public int getTimeout() {
		return timeout;
	}

	public String getUrl() {
		return url;
	}
}
