package com.example.httpreading.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import com.example.httpreading.dto.profile.ProfileDtos.ManualStyleProfileRequest;
import com.example.httpreading.dto.profile.ProfileDtos.ManualStyleProfileResponse;
import com.example.httpreading.dto.profile.ProfileDtos.ProfileOverviewResponse;
import com.example.httpreading.dto.profile.ProfileDtos.StyleProfileDto;
import com.example.httpreading.security.JwtService;
import com.example.httpreading.service.profile.ProfileUpdateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProfileController.class)
@AutoConfigureMockMvc(addFilters = false)
class ProfileControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ProfileUpdateService profileUpdateService;

    @MockBean
    private JwtService jwtService;

    @Test
    void overviewReturnsStructuredStyleProfile() throws Exception {
        when(profileUpdateService.overview("42", null)).thenReturn(new ProfileOverviewResponse(
            "42",
            styleProfile("42"),
            List.of(),
            List.of()));

        mockMvc.perform(get("/api/profile").param("userId", "42"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userId").value("42"))
            .andExpect(jsonPath("$.data.styleProfile.summary").value("用户喜欢完整案例。"))
            .andExpect(jsonPath("$.data.styleProfile.avoidance[0]").value("教科书式"));
    }

    @Test
    void updateStyleForLoginUserReturnsStructuredDto() throws Exception {
        when(profileUpdateService.updateStyleManually(any())).thenReturn(new ManualStyleProfileResponse(
            "success",
            "42",
            styleProfile("42"),
            List.of()));
        ManualStyleProfileRequest request = new ManualStyleProfileRequest(
            "42", null, "直接讲故事", "详细", true, true, false, List.of("模板化"), "用户喜欢完整案例。");

        mockMvc.perform(put("/api/profile/style")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("success"))
            .andExpect(jsonPath("$.data.userId").value("42"))
            .andExpect(jsonPath("$.data.styleProfile.explanationStyle").value("直接讲故事"));

        verify(profileUpdateService).updateStyleManually(any());
    }

    @Test
    void updateStyleForGuestSessionReturnsGuestUser() throws Exception {
        when(profileUpdateService.updateStyleManually(any())).thenReturn(new ManualStyleProfileResponse(
            "success_with_warning",
            "guest:session-1",
            styleProfile("guest:session-1"),
            List.of("sync_failed:style")));
        ManualStyleProfileRequest request = new ManualStyleProfileRequest(
            null, "session-1", "", "", false, false, false, List.of(), "");

        mockMvc.perform(put("/api/profile/style")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userId").value("guest:session-1"))
            .andExpect(jsonPath("$.data.warnings[0]").value("sync_failed:style"));
    }

    private StyleProfileDto styleProfile(String userId) {
        return new StyleProfileDto(
            1L,
            userId,
            "直接讲故事",
            "详细",
            true,
            true,
            false,
            List.of("教科书式"),
            "用户喜欢完整案例。",
            0.5d,
            LocalDateTime.now());
    }
}
