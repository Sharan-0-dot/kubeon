package com.sharan.kubeon.api;

import com.sharan.kubeon.api.dto.HealthCheckResult;
import com.sharan.kubeon.api.exception.ResourceNotFoundException;
import com.sharan.kubeon.detection.BadStateReason;
import com.sharan.kubeon.detection.DetectedIssue;
import com.sharan.kubeon.incident.model.Incident;
import com.sharan.kubeon.incident.model.IncidentStatus;
import com.sharan.kubeon.incident.model.TriggerType;
import com.sharan.kubeon.incident.service.IncidentService;
import com.sharan.kubeon.reasoning.ConfidenceLevel;
import com.sharan.kubeon.reasoning.Diagnosis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class IncidentControllerTest {

    @Mock
    private IncidentService incidentService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new IncidentController(incidentService), new HealthController(incidentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private Incident createIncident(UUID id, String ns, String pod) {
        DetectedIssue issue = new DetectedIssue(ns, pod, BadStateReason.OOM_KILLED, Instant.now(), "Memory limit exceeded");
        Diagnosis diagnosis = new Diagnosis("Ran out of memory", ConfidenceLevel.HIGH, "Increase memory limit", List.of("getPodDetails"), "gemini-2.0-flash");
        return new Incident(id, ns, pod, issue, null, diagnosis, IncidentStatus.DIAGNOSED, TriggerType.AUTO_DETECTED, true, Instant.now());
    }

    @Test
    void testGetIncidentsReturnsList() throws Exception {
        UUID id = UUID.randomUUID();
        Incident incident = createIncident(id, "default", "oom-pod");
        when(incidentService.listIncidents(null)).thenReturn(List.of(incident));

        mockMvc.perform(get("/api/incidents"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].namespace").value("default"))
                .andExpect(jsonPath("$[0].podName").value("oom-pod"))
                .andExpect(jsonPath("$[0].reason").value("OOM_KILLED"))
                .andExpect(jsonPath("$[0].status").value("DIAGNOSED"))
                .andExpect(jsonPath("$[0].diagnosis.confidence").value("HIGH"));
    }

    @Test
    void testGetIncidentsFilteredByNamespace() throws Exception {
        UUID id = UUID.randomUUID();
        Incident incident = createIncident(id, "prod", "prod-pod");
        when(incidentService.listIncidents("prod")).thenReturn(List.of(incident));

        mockMvc.perform(get("/api/incidents").param("namespace", "prod"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].namespace").value("prod"));
    }

    @Test
    void testGetIncidentByIdFound() throws Exception {
        UUID id = UUID.randomUUID();
        Incident incident = createIncident(id, "default", "oom-pod");
        when(incidentService.getIncident(id)).thenReturn(Optional.of(incident));

        mockMvc.perform(get("/api/incidents/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.diagnosis.rootCauseHypothesis").value("Ran out of memory"));
    }

    @Test
    void testGetIncidentByIdNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(incidentService.getIncident(id)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/incidents/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value(containsString("Incident not found with ID")));
    }

    @Test
    void testInvestigatePodSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        Incident incident = createIncident(id, "default", "manual-pod");
        when(incidentService.investigate("default", "manual-pod")).thenReturn(incident);

        mockMvc.perform(post("/api/investigate/default/manual-pod"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.podName").value("manual-pod"))
                .andExpect(jsonPath("$.status").value("DIAGNOSED"));
    }

    @Test
    void testInvestigatePodNotFound() throws Exception {
        when(incidentService.investigate("default", "missing-pod"))
                .thenThrow(new ResourceNotFoundException("Pod 'missing-pod' not found in namespace 'default'"));

        mockMvc.perform(post("/api/investigate/default/missing-pod"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value(containsString("Pod 'missing-pod' not found")));
    }

    @Test
    void testHealthEndpoint() throws Exception {
        HealthCheckResult health = new HealthCheckResult("OK", 5, 0, List.of());
        when(incidentService.runHealthCheck()).thenReturn(health);

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.checkedPodCount").value(5))
                .andExpect(jsonPath("$.issueCount").value(0))
                .andExpect(jsonPath("$.issues").isEmpty());
    }
}
