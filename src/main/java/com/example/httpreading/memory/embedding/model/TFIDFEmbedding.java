package com.example.httpreading.memory.embedding.model;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 轻量级 TF-IDF 嵌入实现（无外部依赖）
 *
 * 用法：
 *   TFIDFEmbedding emb = new TFIDFEmbedding(1000);
 *   emb.fit(corpus);
 *   double[] v = emb.encode("some text");
 */
public class TFIDFEmbedding implements EmbeddingModel {

    private final int maxFeatures;
    private List<String> vocabList;               // 词表（长度 = dimension）
    private Map<String, Integer> vocabIndex;     // 词 -> 索引
    private double[] idf;                        // idf 值
    private boolean isFitted = false;

    public TFIDFEmbedding() {
        this(1000);
    }

    public TFIDFEmbedding(int maxFeatures) {
        this.maxFeatures = maxFeatures;
        this.vocabList = new ArrayList<>();
        this.vocabIndex = new HashMap<>();
    }

    /**
     * Fit vocabulary & idf from corpus
     */
    public void fit(List<String> documents) {
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("documents must not be null or empty");
        }

        // 统计每个文档的词集合（用于计算df）
        Map<String, Integer> df = new HashMap<>();
        for (String doc : documents) {
            Set<String> seen = tokenizeToSet(doc);
            for (String term : seen) {
                df.put(term, df.getOrDefault(term, 0) + 1);
            }
        }

        // 按 df 值降序选择 top maxFeatures 词作为词表
        this.vocabList = df.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(this.maxFeatures)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        this.vocabIndex = new HashMap<>();
        for (int i = 0; i < vocabList.size(); i++) {
            vocabIndex.put(vocabList.get(i), i);
        }

        int nDocs = documents.size();
        this.idf = new double[vocabList.size()];
        for (int i = 0; i < vocabList.size(); i++) {
            int docFreq = df.getOrDefault(vocabList.get(i), 0);
            // idf smoothing: log((N)/(df+1)) + 1
            this.idf[i] = Math.log((double) (nDocs) / (docFreq + 1.0)) + 1.0;
        }

        this.isFitted = true;
    }

    /**
     * Encode single text -> vector
     */
    @Override
    public double[] encode(String text) {
        if (!isFitted) {
            throw new IllegalStateException("TF-IDF model not fitted, call fit() first");
        }
        return encodeOne(text);
    }

    /**
     * Encode multiple texts -> list of vectors
     */
    @Override
    public Object encode(List<String> texts) {
        if (!isFitted) {
            throw new IllegalStateException("TF-IDF model not fitted, call fit() first");
        }
        List<double[]> vectors = new ArrayList<>(texts.size());
        for (String t : texts) {
            vectors.add(encodeOne(t));
        }
        return vectors;
    }

    private double[] encodeOne(String text) {
        double[] vec = new double[vocabList.size()];
        if (text == null || text.isEmpty()) {
            return vec; // 全零向量
        }

        // 计算 term frequency（raw count）
        String[] tokens = tokenize(text);
        if (tokens.length == 0) return vec;
        Map<Integer, Integer> counts = new HashMap<>();
        for (String tok : tokens) {
            Integer idx = vocabIndex.get(tok);
            if (idx != null) {
                counts.put(idx, counts.getOrDefault(idx, 0) + 1);
            }
        }

        if (counts.isEmpty()) return vec;

        // 计算 tf-idf（使用 tf * idf），然后 L2 归一化
        double norm = 0.0;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            int idx = e.getKey();
            double tf = e.getValue(); // raw count
            double val = tf * idf[idx];
            vec[idx] = val;
            norm += val * val;
        }
        norm = Math.sqrt(norm);
        if (norm > 0.0) {
            for (int i = 0; i < vec.length; i++) {
                vec[i] = vec[i] / norm;
            }
        }
        return vec;
    }

    /**
     * 简单分词：非字母/数字断开，小写化，忽略空 token
     */
    private String[] tokenize(String text) {
        return Arrays.stream(text.toLowerCase().split("\\W+"))
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    /**
     * 分词为集合（用于计算 document frequency）
     */
    private Set<String> tokenizeToSet(String text) {
        return Arrays.stream(text.toLowerCase().split("\\W+"))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    @Override
    public int getDimension() {
        return isFitted ? vocabList.size() : this.maxFeatures;
    }

    /**
     * 是否已 fit
     */
    public boolean isFitted() {
        return isFitted;
    }
}
