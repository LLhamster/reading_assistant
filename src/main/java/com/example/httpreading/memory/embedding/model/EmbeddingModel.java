package com.example.httpreading.memory.embedding.model;

import java.util.List;

/**
 * 嵌入模型基类（最小接口）
 */
public interface EmbeddingModel {
    
    /**
     * 将文本编码为向量
     */
    Object encode(String text);
    
    /**
     * 将多个文本编码为向量
     */
    Object encode(List<String> texts);
    
    /**
     * 获取向量维度
     */
    int getDimension();
}