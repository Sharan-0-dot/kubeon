package com.sharan.kubeon.api;

import com.sharan.kubeon.api.dto.IncidentResponse;
import com.sharan.kubeon.api.exception.ResourceNotFoundException;
import com.sharan.kubeon.incident.model.Incident;
import com.sharan.kubeon.incident.service.IncidentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class IncidentController {

    private final IncidentService incidentService;

    public IncidentController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @GetMapping("/incidents")
    public ResponseEntity<List<IncidentResponse>> getIncidents(
            @RequestParam(required = false) String namespace) {
        List<IncidentResponse> responses = incidentService.listIncidents(namespace).stream()
                .map(IncidentResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/incidents/{id}")
    public ResponseEntity<IncidentResponse> getIncidentById(@PathVariable UUID id) {
        return incidentService.getIncident(id)
                .map(IncidentResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResourceNotFoundException("Incident not found with ID: " + id));
    }

    @PostMapping("/investigate/{namespace}/{pod}")
    public ResponseEntity<IncidentResponse> investigatePod(
            @PathVariable String namespace,
            @PathVariable String pod) {
        Incident incident = incidentService.investigate(namespace, pod);
        return ResponseEntity.ok(IncidentResponse.from(incident));
    }
}
