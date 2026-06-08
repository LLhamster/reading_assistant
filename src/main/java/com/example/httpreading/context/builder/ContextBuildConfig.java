package com.example.httpreading.context.builder;

public class ContextBuildConfig {
    private int maxTokens = 8000;
    private double reserveRatio = 0.15d;
    private double minRelevance = 0.05d;
    private boolean compressionEnabled = true;

    public int availableTokens() {
        return (int) (maxTokens * (1.0d - reserveRatio));
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public double getReserveRatio() {
        return reserveRatio;
    }

    public void setReserveRatio(double reserveRatio) {
        this.reserveRatio = reserveRatio;
    }

    public double getMinRelevance() {
        return minRelevance;
    }

    public void setMinRelevance(double minRelevance) {
        this.minRelevance = minRelevance;
    }

    public boolean isCompressionEnabled() {
        return compressionEnabled;
    }

    public void setCompressionEnabled(boolean compressionEnabled) {
        this.compressionEnabled = compressionEnabled;
    }
}
