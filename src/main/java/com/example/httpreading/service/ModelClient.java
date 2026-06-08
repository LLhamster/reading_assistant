package com.example.httpreading.service;

import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class ModelClient {
    private static final Logger log = LoggerFactory.getLogger(ModelClient.class);

    private static final OkHttpClient HTTP_CLIENT =
            new OkHttpClient.Builder()
                    .readTimeout(300, TimeUnit.SECONDS)
                    .build();

    @Value("${model.apiKey:}")
    private String apiKey;

    @Value("${model.dashscope.apiKey:${model.embedding.apiKey:}}")
    private String dashscopeApiKey;

    @Value("${model.dashscope.embeddingModel:${model.embedding.embeddingModel:text-embedding-v3}}")
    private String dashscopeEmbeddingModel;

    public String chat(String question) {
        try {
            JSONObject root = new JSONObject();
            root.put("model", "moonshot-v1-8k");
            JSONArray messages = new JSONArray();
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", question);
            messages.put(userMsg);
            root.put("messages", messages);
            root.put("stream", false);

            MediaType mediaType = MediaType.parse("application/json");
            RequestBody body = RequestBody.create(mediaType, root.toString());

            Request.Builder builder = new Request.Builder()
                    .url("https://api.moonshot.cn/v1/chat/completions")
                    .method("POST", body)
                    .addHeader("Content-Type", "application/json");
            if (apiKey != null && !apiKey.isBlank()) {
                builder.addHeader("Authorization", "Bearer " + apiKey);
            }

            Request request = builder.build();
            String raw;
            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                raw = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    log.warn("模型 HTTP 请求失败 - code:{}, message:{}, body: {}",
                        response.code(), response.message(), truncateLog(raw));
                    return "模型接口请求失败: " + response.code();
                }
            }

            log.debug("模型原始返回: {}", truncateLog(raw));

            JSONObject json = new JSONObject(raw);
            JSONArray choices = json.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject first = choices.getJSONObject(0);
                JSONObject msg = first.optJSONObject("message");
                if (msg != null) {
                    return msg.optString("content", "");
                }
            }
            return "模型返回格式不符合预期";
        } catch (IOException e) {
            log.warn("调用模型接口异常: {}", e.getMessage());
            return "调用模型接口异常: " + e.getMessage();
        }
    }

    private String truncateLog(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000) + "...";
    }

    /**
     * 调用 Kimi embedding API，将文本转为 1024 维向量
     * @param text 输入文本（最长支持 512 字符，超过则截断）
     * @return 1024 维向量列表
     */
    public List<Float> embedding(String text) {
        try {
            // 截断超长文本（embedding 模型最大输入 512 字符）
            if (text != null && text.length() > 512) {
                text = text.substring(0, 512);
            }

            JSONObject root = new JSONObject();
            root.put("model", "moonshot-text-embedding-v1");
            root.put("input", text);

            MediaType mediaType = MediaType.parse("application/json");
            RequestBody body = RequestBody.create(mediaType, root.toString());

            Request.Builder builder = new Request.Builder()
                    .url("https://api.moonshot.cn/v1/embeddings")
                    .method("POST", body)
                    .addHeader("Content-Type", "application/json");
            if (apiKey != null && !apiKey.isBlank()) {
                builder.addHeader("Authorization", "Bearer " + apiKey);
            }

            Request request = builder.build();
            Response response = HTTP_CLIENT.newCall(request).execute();

            if (!response.isSuccessful()) {
                System.out.println("Embedding HTTP error: " + response.code() + " " + response.message());
                return new ArrayList<>();
            }

            String raw = response.body().string();
            JSONObject json = new JSONObject(raw);
            JSONArray data = json.optJSONArray("data");
            if (data != null && data.length() > 0) {
                JSONArray embedding = data.getJSONObject(0).optJSONArray("embedding");
                if (embedding != null) {
                    List<Float> result = new ArrayList<>(embedding.length());
                    for (int i = 0; i < embedding.length(); i++) {
                        result.add((float) embedding.getDouble(i));
                    }
                    return result;
                }
            }
            return new ArrayList<>();
        } catch (IOException e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    /**
     * 调用 DashScope embedding API，将文本转为向量
     * @param text 输入文本（DashScope 支持较长的输入）
     * @return 向量列表
     */
    public List<Float> embeddingByDashScope(String text) {
        try {
            JSONObject root = new JSONObject();
            root.put("model", dashscopeEmbeddingModel);
            root.put("input", text);

            MediaType mediaType = MediaType.parse("application/json");
            RequestBody body = RequestBody.create(mediaType, root.toString());

            Request.Builder builder = new Request.Builder()
                    .url("https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings")
                    .method("POST", body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Bearer " + dashscopeApiKey);

            Request request = builder.build();
            Response response = HTTP_CLIENT.newCall(request).execute();

            if (!response.isSuccessful()) {
                System.out.println("DashScope Embedding HTTP error: " + response.code() + " " + response.message());
                return new ArrayList<>();
            }

            String raw = response.body().string();
            JSONObject json = new JSONObject(raw);
            JSONArray data = json.optJSONArray("data");
            if (data != null && data.length() > 0) {
                JSONArray embedding = data.getJSONObject(0).optJSONArray("embedding");
                if (embedding != null) {
                    List<Float> result = new ArrayList<>(embedding.length());
                    for (int i = 0; i < embedding.length(); i++) {
                        result.add((float) embedding.getDouble(i));
                    }
                    return result;
                }
            }
            return new ArrayList<>();
        } catch (IOException e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
}
