package com.sharan.kubeon;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class ClusterConnectivityCheck implements CommandLineRunner {

    private final KubernetesClient client;

    public ClusterConnectivityCheck(KubernetesClient client) {
        this.client = client;
    }

    @Override
    public void run(String... args) {
        client.pods().inAnyNamespace().list().getItems()
                .forEach(pod -> System.out.println(
                        pod.getMetadata().getNamespace() + " / " + pod.getMetadata().getName()
                ));
    }
}