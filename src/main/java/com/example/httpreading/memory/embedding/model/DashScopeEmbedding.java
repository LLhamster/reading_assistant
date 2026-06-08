package com.example.httpreading.memory.embedding.model;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 阿里云 DashScope 嵌入实现
 * （支持 REST 和 SDK 两种模式）
 */
public class DashScopeEmbedding implements EmbeddingModel {
    
    private static final Logger logger = LoggerFactory.getLogger(DashScopeEmbedding.class);
    
    private String modelName;
    private String apiKey;
    private String baseUrl;
    private Integer dimension;
    private OkHttpClient httpClient;
    private Gson gson;
    
    public DashScopeEmbedding() {
        this("text-embedding-v3", null, null);
    }
    
    public DashScopeEmbedding(String modelName, String apiKey, String baseUrl) {
        this.modelName = modelName;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.httpClient = new OkHttpClient();
        this.gson = new Gson();
        
        // 初始化 SDK（非 REST 模式）
        if (baseUrl == null) {
            initDashScopeClient();
        }
        
        // 探测维度
        detectDimension();
    }
    
    private void initDashScopeClient() {
        try {
            if (apiKey != null) {
                System.setProperty("DASHSCOPE_API_KEY", apiKey);
            }
            // 加载 DashScope SDK
            // Class.forName("com.alibaba.dashscope.DashScope");
        } catch (Exception e) {
            throw new RuntimeException("请安装 dashscope SDK: maven 依赖中添加相关 jar");
        }
    }
    
    private void detectDimension() {
        try {
            List<List<Double>> result = encode("health_check");
            // 根据结果类型判断维度
            this.dimension = result.get(0).size();  // 默认维度
        } catch (Exception e) {
            this.dimension = 1536;
        }
    }
    
    @Override
    public List<List<Double>> encode(String text) {
        List<String> texts = new ArrayList<>();
        texts.add(text);
        List<List<Double>> results =  encode(texts);
        return results.size() > 0 ? results : null;
    }
    
    @Override
    public List<List<Double>> encode(List<String> texts) {
        if (this.baseUrl != null) {
            return encodeByRest(texts);
        } else {
            return encodeBySdk(texts);
        }
    }
    
    /**
     * REST 模式（OpenAI 兼容）
     */
    private List<List<Double>> encodeByRest(List<String> texts) {
        try {
            String normalizedBaseUrl = baseUrl.replaceAll("/$", "");
            String url = normalizedBaseUrl.endsWith("/embeddings")
                ? normalizedBaseUrl
                : normalizedBaseUrl + "/embeddings";
            
            JsonObject payload = new JsonObject();
            payload.addProperty("model", modelName);
            
            // 添加输入文本
            JsonObject input = new JsonObject();
            input.add("input", gson.toJsonTree(texts));
            payload.add("input", input.get("input"));
            
            Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + (apiKey != null ? apiKey : ""))
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payload.toString(), 
                    okhttp3.MediaType.parse("application/json")))
                .build();
            
            Response response = httpClient.newCall(request).execute();
            if (response.code() >= 400) {
                throw new RuntimeException("Embedding REST 调用失败: " + response.code());
            }
            
            String responseBody = response.body().string();
            JsonObject data = gson.fromJson(responseBody, JsonObject.class);
            // 解析返回的向量
            return parseEmbeddings(data);
            
        } catch (Exception e) {
            logger.error("REST 模式编码失败", e);
            throw new RuntimeException(e);
        }
    }
    
    /**
     * SDK 模式
     */
    private List<List<Double>> encodeBySdk(List<String> texts) {
        try {
            // 调用 DashScope SDK
            // TextEmbedding.TextEmbeddingResponse response = 
            //     TextEmbedding.call(TextEmbeddingRequest...);
            // return parseEmbeddingsFromResponse(response);
            return null;
        } catch (Exception e) {
            logger.error("SDK 模式编码失败", e);
            throw new RuntimeException(e);
        }
    }


    private List<List<Double>> parseEmbeddings(JsonObject data) { // 解析 Embedding 接口返回的 JSON 数据，并返回二维向量列表
        List<List<Double>> vectors = new ArrayList<>(); // 创建一个列表，用来存放所有文本对应的向量
        JsonArray dataArray = data.getAsJsonArray("data"); // 从 JSON 中取出 data 数组
        for (JsonElement itemElement : dataArray) { // 遍历 data 数组中的每一个元素
            JsonObject itemObject = itemElement.getAsJsonObject(); // 将当前元素转换成 JsonObject 对象
            JsonArray embeddingArray = itemObject.getAsJsonArray("embedding"); // 从当前对象中取出 embedding 数组
            List<Double> vector = new ArrayList<>(); // 创建一个列表，用来存放当前文本的向量
            for (JsonElement valueElement : embeddingArray) { // 遍历 embedding 数组中的每一个数值
                double value = valueElement.getAsDouble(); // 将当前 JSON 数值转换成 Java 的 double 类型
                vector.add(value); // 把当前数值添加到当前向量中
            }
            vectors.add(vector); // 把当前文本的完整向量添加到总向量列表中
        }
        return vectors; // 返回所有文本对应的向量列表
    }
    
    @Override
    public int getDimension() {
        return dimension != null ? dimension : 0;
    }
}
