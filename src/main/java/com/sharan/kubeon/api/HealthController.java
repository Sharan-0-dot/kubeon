package com.sharan.kubeon.api;

import com.sharan.kubeon.api.dto.HealthCheckResult;
import com.sharan.kubeon.incident.service.IncidentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final IncidentService incidentService;

    public HealthController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthCheckResult> getClusterHealth() {
        HealthCheckResult result = incidentService.runHealthCheck();
        return ResponseEntity.ok(result);
    }
}
