package com.openmc.webapp.config;

import com.openmc.webapp.kubernetes.KubernetesStatefulSetScaleClient;
import com.openmc.webapp.kubernetes.StatefulSetScaleClient;
import com.openmc.webapp.service.ServerStateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The context must come up in sleep-aware mode from nothing but the env the chart
 * sets plus a mounted ServiceAccount directory — and must still come up when that
 * directory is incomplete, because a mis-mounted token should degrade the dashboard,
 * not take it down.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "minecraft.server.host=localhost",
    "minecraft.server.admin-username=admin",
    "minecraft.server.admin-password=admin",
    "data.storage.base-directory=data",
    "sleep.aware.enabled=true",
    "sleep.aware.statefulset-name=oak-omcsi-minecraft-wrapper",
    "KUBERNETES_SERVICE_HOST=10.96.0.1",
    "KUBERNETES_SERVICE_PORT=443"
})
@DisplayName("SleepAwareConfig Tests")
class SleepAwareConfigTest {

    @TempDir
    static Path serviceAccountDir;

    @DynamicPropertySource
    static void serviceAccountFiles(DynamicPropertyRegistry registry) throws IOException {
        Files.writeString(serviceAccountDir.resolve("namespace"), "oak\n");
        Files.writeString(serviceAccountDir.resolve("token"), "sa-token\n");
        // deliberately no ca.crt: the client must still be built
        registry.add("sleep.aware.service-account-dir", () -> serviceAccountDir.toString());
    }

    @Autowired
    private StatefulSetScaleClient scaleClient;

    @Autowired
    private ServerStateService serverStateService;

    @Test
    @DisplayName("builds the Kubernetes client from the pod's environment and ServiceAccount mount")
    void buildsClientFromEnvironment() {
        KubernetesStatefulSetScaleClient k8s = assertInstanceOf(KubernetesStatefulSetScaleClient.class, scaleClient);
        assertEquals(
                "https://10.96.0.1:443/apis/apps/v1/namespaces/oak/statefulsets/oak-omcsi-minecraft-wrapper/scale",
                k8s.getScaleUrl());
        assertTrue(serverStateService.isSleepAware());
    }

    @Test
    @DisplayName("an absent or unparseable cluster CA means the JVM default trust store, not a failure")
    void caFallbacks(@TempDir Path dir) throws IOException {
        assertNull(SleepAwareConfig.sslContextTrusting(dir.resolve("missing-ca.crt")));

        Path garbage = dir.resolve("ca.crt");
        Files.writeString(garbage, "not a certificate");
        assertNull(SleepAwareConfig.sslContextTrusting(garbage));
    }
}
