package com.example.httpreading.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ModelClientChatOptionsTest {
    @Test
    void exposesBackwardCompatibleDefaultAndDeterministicOptions() {
        assertNull(ModelClient.ChatOptions.defaults().temperature());
        assertEquals(0.0, ModelClient.ChatOptions.deterministic().temperature());
        assertThrows(IllegalArgumentException.class,
            () -> new ModelClient.ChatOptions(2.1));
    }
}
