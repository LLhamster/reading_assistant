package com.example.httpreading.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import com.example.httpreading.domain.cognition.ConceptCandidateStatus;
import com.example.httpreading.domain.cognition.ConceptResolutionDecision;
import com.example.httpreading.domain.cognition.ConceptStatus;
import com.example.httpreading.domain.cognition.ConfidenceLevel;
import com.example.httpreading.dto.cognition.ConceptCandidateDto;
import com.example.httpreading.dto.cognition.ConceptCandidateRecordDto;
import com.example.httpreading.dto.cognition.ConceptManagementResponse;
import com.example.httpreading.dto.cognition.ConceptResolutionResult;
import com.example.httpreading.dto.cognition.ScoreBreakdownDto;
import com.example.httpreading.security.JwtService;
import com.example.httpreading.service.cognition.ConceptManagementService;
import com.example.httpreading.service.cognition.ConceptResolverService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CognitionConceptController.class)
@AutoConfigureMockMvc(addFilters = false)
class CognitionConceptControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ConceptResolverService conceptResolverService;

    @MockBean
    private ConceptManagementService conceptManagementService;

    @MockBean
    private JwtService jwtService;

    @Test
    void resolutionReturnsConceptResolution() throws Exception {
        when(conceptResolverService.getResolution("evt-1")).thenReturn(new ConceptResolutionResult(
            "evt-1",
            "机会主义",
            101L,
            List.of(new ConceptCandidateDto(101L, "机会主义", 0.91d)),
            0.88d,
            ConfidenceLevel.HIGH,
            ConceptResolutionDecision.LINK_EXISTING,
            new ScoreBreakdownDto(0.85d, 1.0d, 0.9d, 0.7d, 0.8d),
            List.of("划词与概念一致"),
            "划词与正式概念完全匹配"));

        mockMvc.perform(get("/api/cognition/concepts/resolutions/evt-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.primaryConceptName").value("机会主义"))
            .andExpect(jsonPath("$.data.matchedConceptId").value(101L))
            .andExpect(jsonPath("$.data.decision").value("LINK_EXISTING"));
    }

    @Test
    void candidatesDefaultToPending() throws Exception {
        when(conceptManagementService.listCandidates(ConceptCandidateStatus.PENDING)).thenReturn(List.of(
            new ConceptCandidateRecordDto(
                1L,
                "evt-2",
                "政治投机",
                null,
                0.62d,
                0.8d,
                0.5d,
                0.4d,
                0.0d,
                0.8d,
                ConceptCandidateStatus.PENDING,
                "中置信候选",
                LocalDateTime.now())));

        mockMvc.perform(get("/api/cognition/concepts/candidates"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].candidateName").value("政治投机"))
            .andExpect(jsonPath("$.data[0].status").value("PENDING"));
    }

    @Test
    void confirmCandidateDelegatesToService() throws Exception {
        when(conceptManagementService.confirmCandidate(eq(1L), any())).thenReturn(new ConceptManagementResponse(
            "confirmed",
            101L,
            "机会主义",
            ConceptStatus.CONFIRMED,
            1L,
            ConceptCandidateStatus.ACCEPTED));

        mockMvc.perform(post("/api/cognition/concepts/candidates/1/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetConceptId\":101,\"aliasName\":\"政治投机\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("confirmed"))
            .andExpect(jsonPath("$.data.conceptId").value(101L));

        verify(conceptManagementService).confirmCandidate(eq(1L), any());
    }
}
