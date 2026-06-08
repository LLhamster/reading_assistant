package com.example.httpreading.memory.embedding.factory;

import com.example.httpreading.memory.embedding.model.DashScopeEmbedding;
import com.example.httpreading.memory.embedding.model.EmbeddingModel;
import com.example.httpreading.memory.embedding.model.LocalTransformerEmbedding;
import com.example.httpreading.memory.embedding.model.TFIDFEmbedding;

/**
 * 嵌入模型工厂
 */
public class EmbeddingModelFactory {
    
    public static EmbeddingModel createEmbeddingModel(String modelType, 
                                                       String... args) {
        switch (modelType.toLowerCase()) {
            case "local":
            case "sentence_transformer":
            case "huggingface":
                String modelName = args.length > 0 ? args[0] : null;
                return modelName != null 
                    ? new LocalTransformerEmbedding(modelName)
                    : new LocalTransformerEmbedding();
            
            case "dashscope":
                String dsModelName = args.length > 0 ? args[0] : "text-embedding-v3";
                String apiKey = args.length > 1 ? args[1] : null;
                String baseUrl = args.length > 2 ? args[2] : null;
                return new DashScopeEmbedding(dsModelName, apiKey, baseUrl);
            
            case "tfidf":
                int maxFeatures = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
                return new TFIDFEmbedding(maxFeatures);
            
            default:
                throw new IllegalArgumentException("不支持的模型类型: " + modelType);
        }
    }
}