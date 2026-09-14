package com.openmc.webapp.kubernetes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Supplier;

/**
 * Reads and patches a StatefulSet's {@code scale} subresource through the Kubernetes
 * API using nothing but a {@link RestTemplate} and the pod's own ServiceAccount token.
 *
 * <p>Only the {@code scale} subresource is touched, which is what the chart's Role
 * grants: {@code get} and {@code patch} on {@code statefulsets/scale} for the one
 * named StatefulSet. A full Kubernetes client library would be a large dependency for
 * two requests.
 */
public class KubernetesStatefulSetScaleClient implements StatefulSetScaleClient {

    private static final Logger log = LoggerFactory.getLogger(KubernetesStatefulSetScaleClient.class);

    private static final MediaType MERGE_PATCH_JSON = MediaType.valueOf("application/merge-patch+json");

    private final RestTemplate restTemplate;
    private final String scaleUrl;
    private final Supplier<String> tokenSupplier;

    /**
     * @param restTemplate  a template that trusts the cluster's CA (see
     *                      {@code SleepAwareConfig}) and supports the PATCH method
     * @param apiBaseUrl    e.g. {@code https://10.96.0.1:443}
     * @param namespace     the namespace holding the StatefulSet
     * @param name          the StatefulSet's name
     * @param tokenSupplier reads the ServiceAccount token on every call, because the
     *                      kubelet rotates a projected token and rewrites the file
     */
    public KubernetesStatefulSetScaleClient(RestTemplate restTemplate, String apiBaseUrl, String namespace,
                                            String name, Supplier<String> tokenSupplier) {
        this.restTemplate = restTemplate;
        this.scaleUrl = String.format("%s/apis/apps/v1/namespaces/%s/statefulsets/%s/scale",
                apiBaseUrl, namespace, name);
        this.tokenSupplier = tokenSupplier;
    }

    /** The URL of the scale subresource this client operates on; exposed for logging and tests. */
    public String getScaleUrl() {
        return scaleUrl;
    }

    @Override
    public OptionalInt getReplicas() {
        try {
            ResponseEntity<Scale> response = restTemplate.exchange(
                    scaleUrl, HttpMethod.GET, new HttpEntity<>(authHeaders()), Scale.class);
            Scale scale = response.getBody();
            if (scale == null || scale.spec() == null || scale.spec().replicas() == null) {
                log.warn("Kubernetes returned a scale object without spec.replicas for {}", scaleUrl);
                return OptionalInt.empty();
            }
            return OptionalInt.of(scale.spec().replicas());
        } catch (RestClientException e) {
            log.debug("Failed to read StatefulSet scale from {}: {}", scaleUrl, e.getMessage());
            return OptionalInt.empty();
        }
    }

    @Override
    public boolean scaleTo(int replicas) {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MERGE_PATCH_JSON);
        Map<String, Object> body = Map.of("spec", Map.of("replicas", replicas));
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    scaleUrl, HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);
            boolean accepted = response.getStatusCode().is2xxSuccessful();
            if (!accepted) {
                log.warn("Kubernetes answered {} when scaling {} to {}", response.getStatusCode(), scaleUrl, replicas);
            }
            return accepted;
        } catch (RestClientException e) {
            log.warn("Failed to scale StatefulSet via {} to {}: {}", scaleUrl, replicas, e.getMessage());
            return false;
        }
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        String token = tokenSupplier.get();
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token.trim());
        }
        return headers;
    }

    /** The subset of {@code autoscaling/v1 Scale} the client reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Scale(ScaleSpec spec) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScaleSpec(Integer replicas) {
    }
}
