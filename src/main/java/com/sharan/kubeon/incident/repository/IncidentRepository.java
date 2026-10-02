package com.sharan.kubeon.incident.repository;

import com.sharan.kubeon.incident.model.Incident;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository {

    Incident save(Incident incident);

    Optional<Incident> findById(UUID id);

    List<Incident> findAll();

    List<Incident> findByNamespace(String namespace);

    boolean hasActiveIncident(String namespace, String podName);
}
