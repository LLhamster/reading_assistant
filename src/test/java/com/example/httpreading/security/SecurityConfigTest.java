package com.example.httpreading.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(SecurityConfigTest.ProbeController.class)
@Import({
    SecurityConfig.class,
    JwtAuthFilter.class,
    AuthenticationErrorHandler.class,
    AccessDeniedErrorHandler.class,
    SecurityConfigTest.ProbeController.class
})
@TestPropertySource(properties = "app.cors.allowed-origins=http://localhost:5173")
class SecurityConfigTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtService jwtService;

    @Test
    void protectsAiEndpointsByDefault() throws Exception {
        mockMvc.perform(get("/api/ai/probe"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void protectsAdminEndpointsByDefault() throws Exception {
        mockMvc.perform(get("/api/admin/probe"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void permitsMobileBookReadEndpoints() throws Exception {
        mockMvc.perform(get("/api/mobile/books/probe"))
            .andExpect(status().isOk());
    }

    @Test
    void keepsMcpEndpointsAvailableForConfiguredLocalClient() throws Exception {
        mockMvc.perform(get("/mcp/probe"))
            .andExpect(status().isOk());
    }

    @RestController
    static class ProbeController {
        @GetMapping({"/api/ai/probe", "/api/admin/probe", "/api/mobile/books/probe", "/mcp/probe"})
        String probe() {
            return "ok";
        }
    }
}
