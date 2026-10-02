package com.sharan.kubeon.incident.persistence;

import com.sharan.kubeon.incident.model.IncidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IncidentJpaRepository extends JpaRepository<IncidentEntity, UUID> {

    List<IncidentEntity> findAllByOrderByCreatedAtDesc();

    List<IncidentEntity> findByNamespaceOrderByCreatedAtDesc(String namespace);

    Optional<IncidentEntity> findTopByNamespaceAndPodNameAndStatusNotOrderByCreatedAtDesc(
            String namespace, String podName, IncidentStatus status);
}
