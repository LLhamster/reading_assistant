package com.example.httpreading.memory.embedding.provider;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

import com.example.httpreading.memory.embedding.model.DashScopeEmbedding;
import com.example.httpreading.memory.embedding.model.EmbeddingModel;
import com.example.httpreading.memory.embedding.model.LocalTransformerEmbedding;
import com.example.httpreading.memory.embedding.model.TFIDFEmbedding;

/**
 * 嵌入模型单例提供者（线程安全）
 */
@Component
public class EmbeddingProvider {
    
    private static final Logger logger = LoggerFactory.getLogger(EmbeddingProvider.class);
    
    private static final Object lock = new Object();
    private static volatile EmbeddingModel embedder = null;
    
    /**
     * 获取全局共享的文本嵌入实例（线程安全单例）
     */
    public static EmbeddingModel getTextEmbedder() {
        if (embedder != null) {
            return embedder;
        }
        
        synchronized (lock) {
            if (embedder == null) {
                embedder = new EmbeddingProvider().buildEmbedderFromConfig();
            }
            return embedder;
        }
    }
    
    /**
     * 配置注入字段（可通过 application.properties 配置）
     */
    // 直接读取 application.yml 中的 model 配置
    @Value("${model.embedding.type:dashscope}")
    private String preferredTypeConfig;

    // 读取 model.dashscope.embeddingModel
    @Value("${model.embedding.embeddingModel:}")
    private String modelNameConfig;

    // 读取 model.dashscope.apiKey
    @Value("${model.embedding.apiKey:}")
    private String apiKeyConfig;

    // 读取 model.dashscope.baseUrl
    @Value("${model.embedding.baseUrl:}")
    private String baseUrlConfig;

    @PostConstruct
    private void initEmbedderFromConfig() {
        synchronized (lock) {
            if (embedder == null) {
                embedder = buildEmbedderFromConfig();
            }
        }
    }

    private EmbeddingModel buildEmbedderFromConfig() {
        String preferredType = (preferredTypeConfig != null && !preferredTypeConfig.isBlank()) ? preferredTypeConfig : null;
        if (preferredType == null) {
            preferredType = "dashscope";
        }
        preferredType = preferredType.trim();

        // 根据提供商选择默认模型
        String defaultModel = "dashscope".equals(preferredType)
            ? "text-embedding-v3"
            : "sentence-transformers/all-MiniLM-L6-v2";

        String modelName = (modelNameConfig != null && !modelNameConfig.isBlank()) ? modelNameConfig : System.getenv("EMBED_MODEL_NAME");
        if (modelName == null) {
            modelName = defaultModel;
        }
        modelName = modelName.trim();

        String apiKey = (apiKeyConfig != null && !apiKeyConfig.isBlank()) ? apiKeyConfig : null;
        String baseUrl = (baseUrlConfig != null && !baseUrlConfig.isBlank()) ? baseUrlConfig : null;
        if (baseUrl != null && baseUrl.contains("example")) {
            baseUrl = null;
        }

        // 如果没有 baseUrl，为特定提供商提供占位默认值（请根据实际提供商替换）
        if ((baseUrl == null || baseUrl.isBlank()) && "dashscope".equals(preferredType)) {
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }

        return createEmbeddingModelWithFallback(preferredType, modelName, apiKey, baseUrl);
    }
    
    /**
     * 带回退的创建：dashscope -> local -> tfidf
     */
    private static EmbeddingModel createEmbeddingModelWithFallback(String preferredType, 
                                                                   String modelName,
                                                                   String apiKey,
                                                                   String baseUrl) {
        String[] fallback = {"dashscope", "local", "tfidf"};
        
        // 将首选放最前
        if (!preferredType.equals("dashscope") && !preferredType.equals("local") && !preferredType.equals("tfidf")) {
            preferredType = "local";
        }
        
        // 尝试创建首选模型
        try {
            return createModel(preferredType, modelName, apiKey, baseUrl);
        } catch (Exception e) {
            logger.warn("创建首选模型失败 ({}): {}", preferredType, e.getMessage());
        }
        
        // 回退其他模型
        for (String type : fallback) {
            if (type.equals(preferredType)) {
                continue;
            }
            try {
                logger.info("尝试使用回退模型: {}", type);
                return createModel(type, modelName, apiKey, baseUrl);
            } catch (Exception e) {
                logger.warn("创建回退模型失败 ({}): {}", type, e.getMessage());
            }
        }
        
        throw new RuntimeException("所有嵌入模型都不可用，请安装依赖或检查配置");
    }
    
    private static EmbeddingModel createModel(String type, String modelName, 
                                              String apiKey, String baseUrl) {
        switch (type) {
            case "local":
                return new LocalTransformerEmbedding(modelName);
            case "dashscope":
                return new DashScopeEmbedding(modelName, apiKey, baseUrl);
            case "tfidf":
                return new TFIDFEmbedding();
            default:
                throw new IllegalArgumentException("不支持的模型类型: " + type);
        }
    }
    
    /**
     * 获取向量维度
     */
    public static int getDimension(int defaultDimension) {
        try {
            return getTextEmbedder().getDimension();
        } catch (Exception e) {
            logger.warn("获取维度失败，使用默认值: {}", defaultDimension);
            return defaultDimension;
        }
    }
    
    /**
     * 强制重建嵌入实例
     */
    public static EmbeddingModel refreshEmbedder() {
        synchronized (lock) {
            embedder = new EmbeddingProvider().buildEmbedderFromConfig();
            return embedder;
        }
    }
}
