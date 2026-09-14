package com.openmc.webapp.config;

import com.openmc.webapp.kubernetes.KubernetesStatefulSetScaleClient;
import com.openmc.webapp.kubernetes.StatefulSetScaleClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Collection;
import java.util.function.Supplier;

/**
 * Wires the Kubernetes scale client when the dashboard runs sleep-aware
 * ({@code SLEEP_AWARE_ENABLED=true}, set by the Helm chart's
 * {@code webapp.sleepAware.enabled}). Nothing here is created otherwise, so a
 * single-server deployment — Compose or a chart with the flag off — carries no
 * Kubernetes code path at all.
 *
 * <p>The client talks to the API server the way any in-cluster process does: the
 * address from {@code KUBERNETES_SERVICE_HOST}/{@code KUBERNETES_SERVICE_PORT}, the
 * mounted ServiceAccount token, namespace and CA under
 * {@code /var/run/secrets/kubernetes.io/serviceaccount}. Anything missing is logged
 * and the client is still created; its calls then fail and the status derivation
 * falls back to the pre-sleep-aware behaviour rather than the page breaking.
 */
@Configuration
@ConditionalOnProperty(name = "sleep.aware.enabled", havingValue = "true")
public class SleepAwareConfig {

    private static final Logger log = LoggerFactory.getLogger(SleepAwareConfig.class);

    static final String SERVICE_ACCOUNT_DIR = "/var/run/secrets/kubernetes.io/serviceaccount";

    @Bean
    public StatefulSetScaleClient statefulSetScaleClient(
            @Value("${sleep.aware.statefulset-name:}") String statefulSetName,
            @Value("${sleep.aware.service-account-dir:" + SERVICE_ACCOUNT_DIR + "}") String serviceAccountDir,
            @Value("${KUBERNETES_SERVICE_HOST:}") String apiHost,
            @Value("${KUBERNETES_SERVICE_PORT:443}") String apiPort) {
        Path saDir = Path.of(serviceAccountDir);
        String namespace = readTrimmed(saDir.resolve("namespace"));
        if (statefulSetName.isBlank()) {
            log.warn("Sleep-aware mode is enabled but WRAPPER_STATEFULSET_NAME is not set; "
                    + "the dashboard will fall back to plain wrapper status");
        }
        if (apiHost.isBlank()) {
            log.warn("Sleep-aware mode is enabled but KUBERNETES_SERVICE_HOST is not set; "
                    + "is the dashboard running inside a cluster?");
        }
        if (namespace == null) {
            log.warn("Sleep-aware mode is enabled but no namespace file was found under {}; "
                    + "is the ServiceAccount token mounted?", saDir);
            namespace = "default";
        }
        String apiBaseUrl = "https://" + (apiHost.isBlank() ? "kubernetes.default.svc" : apiHost) + ":" + apiPort;
        Path tokenFile = saDir.resolve("token");
        Supplier<String> tokenSupplier = () -> readTrimmed(tokenFile);

        RestTemplate restTemplate = kubernetesRestTemplate(saDir.resolve("ca.crt"));
        KubernetesStatefulSetScaleClient client = new KubernetesStatefulSetScaleClient(
                restTemplate, apiBaseUrl, namespace, statefulSetName, tokenSupplier);
        log.info("Sleep-aware dashboard enabled; wrapper StatefulSet scale at {}", client.getScaleUrl());
        return client;
    }

    /**
     * A RestTemplate over the JDK's own HttpClient: it supports PATCH (which the
     * default {@code HttpURLConnection} factory does not) and takes an SSLContext, so
     * the cluster CA can be trusted without touching the JVM's global trust store.
     */
    static RestTemplate kubernetesRestTemplate(Path caFile) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
        SSLContext sslContext = sslContextTrusting(caFile);
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(builder.build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        return new RestTemplate(factory);
    }

    /**
     * @return an SSLContext trusting only the certificates in {@code caFile}, or null
     *         (meaning: use the JVM default) when the file is absent or unreadable
     */
    static SSLContext sslContextTrusting(Path caFile) {
        if (!Files.isReadable(caFile)) {
            log.warn("Cluster CA {} is not readable; the Kubernetes API will be contacted with the JVM's default trust store", caFile);
            return null;
        }
        try (InputStream in = Files.newInputStream(caFile)) {
            Collection<? extends Certificate> certs = CertificateFactory.getInstance("X.509").generateCertificates(in);
            if (certs.isEmpty()) {
                log.warn("Cluster CA {} holds no certificates; the Kubernetes API will be contacted with the JVM's default trust store", caFile);
                return null;
            }
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            int i = 0;
            for (Certificate cert : certs) {
                trustStore.setCertificateEntry("cluster-ca-" + (i++), cert);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, tmf.getTrustManagers(), null);
            return context;
        } catch (IOException | java.security.GeneralSecurityException e) {
            log.warn("Could not build a trust store from {}: {}", caFile, e.getMessage());
            return null;
        }
    }

    private static String readTrimmed(Path file) {
        try {
            return Files.readString(file).trim();
        } catch (IOException e) {
            return null;
        }
    }
}
