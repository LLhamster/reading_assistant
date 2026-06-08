package com.example.httpreading.service;


import java.time.LocalDateTime;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.example.httpreading.domain.user.Reading;
import com.example.httpreading.repository.ReadingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.transaction.Transactional;

@Service
public class ReadingService {
    private ReadingRepository readingRepository;
    private RedisTemplate<String, Object> redisTemplate;
    private ObjectMapper objectMapper;
    
    @Value("${cache.enabled:true}")
    private boolean cacheEnabled;
    
    public ReadingService(ReadingRepository readingRepository, 
                          RedisTemplate<String, Object> redisTemplate,
                          ObjectMapper objectMapper){
        this.readingRepository = readingRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Reading getProgress(Long bookId, Long userId){
        // 禁用缓存时直接查数据库
        if (!cacheEnabled) {
            return readingRepository.findByBookIdAndUserId(bookId, userId).orElse(null);
        }
        
        // 随机 TTL，防止雪崩
        int baseTTL = 10;                              // 基础 10 分钟
        int jitter = new Random().nextInt(300);       // 0~299 秒随机抖动
        long actualTTLSeconds = baseTTL * 60 + jitter;  // 600~899 秒
        
        Object cache = redisTemplate.opsForValue().get("progress:userId:" + userId + ":bookId:" + bookId);
        if(cache != null){
            if(cache instanceof Reading) return (Reading)cache;
            // 如果是 LinkedHashMap，用 ObjectMapper 转换
            if(cache instanceof java.util.Map) {
                return objectMapper.convertValue(cache, Reading.class);
            }
            return null;
        }
        else{
            Reading reading = readingRepository.findByBookIdAndUserId(bookId, userId).orElse(null);
            if(reading != null){
                // 缓存击穿保护（互斥锁）
                Boolean lock = redisTemplate.opsForValue().setIfAbsent(
                        "lock:progress:userId:" + userId + ":bookId:" + bookId, 
                        1, 10, TimeUnit.SECONDS);
                if(Boolean.TRUE.equals(lock)){
                    try {
                        redisTemplate.opsForValue().set(
                                "progress:userId:" + userId + ":bookId:" + bookId, 
                                reading, actualTTLSeconds, TimeUnit.SECONDS);
                    } finally {
                        redisTemplate.delete("lock:progress:userId:" + userId + ":bookId:" + bookId);
                    }
                } else {
                    // 等待其他线程构建缓存
                    try {
                        Thread.sleep(50);
                        return getProgress(bookId, userId);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            return reading;
        }
    }

    @Transactional
    public Reading updateProgress(Long bookId, Long userId, Integer index, Integer offset){
        Reading reading = readingRepository.findByBookIdAndUserId(bookId, userId).orElseGet(Reading::new);
        reading.setBookId(bookId);
        reading.setUserId(userId);
        reading.setChapterIndex(index);
        reading.setOffset(offset);
        reading.setUpdatedAt(LocalDateTime.now());
        reading = readingRepository.save(reading);
        redisTemplate.delete("progress:userId:" + userId + ":bookId:" + bookId);
        return reading;
    }

}