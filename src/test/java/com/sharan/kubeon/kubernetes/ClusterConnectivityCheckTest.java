package com.sharan.kubeon.kubernetes;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class ClusterConnectivityCheckTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(KubernetesClient.class, () -> mock(KubernetesClient.class))
            .withUserConfiguration(ClusterConnectivityCheck.class);

    @Test
    void connectivityCheckEnabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ClusterConnectivityCheck.class);
        });
    }

    @Test
    void connectivityCheckExplicitlyEnabled() {
        contextRunner
                .withPropertyValues("kubeon.connectivity-check.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ClusterConnectivityCheck.class);
                });
    }

    @Test
    void connectivityCheckDisabledByProperty() {
        contextRunner
                .withPropertyValues("kubeon.connectivity-check.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ClusterConnectivityCheck.class);
                });
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void runExecutesPodListingSuccessfully() {
        KubernetesClient client = mock(KubernetesClient.class);
        MixedOperation podsOp = mock(MixedOperation.class);
        NonNamespaceOperation inAnyOp = mock(NonNamespaceOperation.class);
        PodList podList = mock(PodList.class);

        Pod pod = new Pod();
        ObjectMeta meta = new ObjectMeta();
        meta.setName("test-pod");
        meta.setNamespace("default");
        pod.setMetadata(meta);

        when(client.pods()).thenReturn(podsOp);
        when(podsOp.inAnyNamespace()).thenReturn(inAnyOp);
        when(inAnyOp.list()).thenReturn(podList);
        when(podList.getItems()).thenReturn(List.of(pod));

        ClusterConnectivityCheck check = new ClusterConnectivityCheck(client);

        assertThatCode(check::run).doesNotThrowAnyException();
        verify(client.pods().inAnyNamespace()).list();
    }
}
