package com.example.httpreading.context.builder;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ContextTokenCounterTest {
    @Test
    void countsDenseJsonAndUrlsInsteadOfOnlyWhitespaceSeparatedWords() {
        String denseJson = "{\"sha\":\"abcdef0123456789\",\"url\":\"https://api.github.com/repos/a/b/commits/1\"}"
            .repeat(100);

        assertTrue(ContextTokenCounter.countTokens(denseJson) > 1000);
    }
}
