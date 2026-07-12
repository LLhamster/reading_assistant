package com.example.httpreading.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AiChatRateLimiter {
    private final ConcurrentMap<String, Deque<Instant>> buckets = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxRequests;
    private final Duration window;

    @Autowired
    public AiChatRateLimiter(@Value("${app.ai.rate-limit.max-requests:20}") int maxRequests,
                             @Value("${app.ai.rate-limit.window-seconds:60}") long windowSeconds) {
        this(Clock.systemUTC(), maxRequests, Duration.ofSeconds(windowSeconds));
    }

    AiChatRateLimiter(Clock clock, int maxRequests, Duration window) {
        this.clock = clock;
        this.maxRequests = Math.max(1, maxRequests);
        this.window = window.isNegative() || window.isZero() ? Duration.ofSeconds(60) : window;
    }

    public boolean tryAcquire(String userKey) {
        String key = userKey == null || userKey.isBlank() ? "anonymous" : userKey;
        Instant now = clock.instant();
        Instant cutoff = now.minus(window);
        Deque<Instant> bucket = buckets.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (bucket) {
            while (!bucket.isEmpty() && bucket.peekFirst().isBefore(cutoff)) {
                bucket.removeFirst();
            }
            if (bucket.size() >= maxRequests) {
                return false;
            }
            bucket.addLast(now);
            return true;
        }
    }
}
