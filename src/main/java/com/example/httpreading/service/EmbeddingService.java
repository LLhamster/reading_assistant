package com.example.httpreading.service;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文本向量化服务
 * 封装 ModelClient 的 embedding 能力
 */
@Service
public class EmbeddingService {

    private final ModelClient modelClient;

    public EmbeddingService(ModelClient modelClient) {
        this.modelClient = modelClient;
    }

    /**
     * 将文本转为向量（使用 DashScope）
     * @param text 输入文本
     * @return 向量
     */
    public List<Float> embed(String text) {
        return modelClient.embeddingByDashScope(text);
    }
}
