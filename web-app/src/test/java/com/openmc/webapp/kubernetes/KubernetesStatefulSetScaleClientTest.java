package com.openmc.webapp.kubernetes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("KubernetesStatefulSetScaleClient Tests")
class KubernetesStatefulSetScaleClientTest {

    private static final String SCALE_URL =
            "https://10.96.0.1:443/apis/apps/v1/namespaces/oak/statefulsets/oak-omcsi-minecraft-wrapper/scale";

    private MockRestServiceServer server;
    private KubernetesStatefulSetScaleClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new KubernetesStatefulSetScaleClient(
                restTemplate, "https://10.96.0.1:443", "oak", "oak-omcsi-minecraft-wrapper", () -> "sa-token\n");
    }

    @Test
    @DisplayName("addresses the scale subresource of the named StatefulSet in its namespace")
    void scaleUrl() {
        assertEquals(SCALE_URL, client.getScaleUrl());
    }

    @Test
    @DisplayName("reads spec.replicas with the ServiceAccount token as a Bearer")
    void readsReplicas() {
        server.expect(requestTo(SCALE_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer sa-token"))
                .andRespond(withSuccess(
                        "{\"kind\":\"Scale\",\"apiVersion\":\"autoscaling/v1\","
                                + "\"metadata\":{\"name\":\"oak-omcsi-minecraft-wrapper\"},"
                                + "\"spec\":{\"replicas\":0},\"status\":{\"replicas\":1,\"selector\":\"x\"}}",
                        MediaType.APPLICATION_JSON));

        assertEquals(OptionalInt.of(0), client.getReplicas());
        server.verify();
    }

    @Test
    @DisplayName("a forbidden read is empty rather than an exception")
    void forbiddenReadIsEmpty() {
        server.expect(requestTo(SCALE_URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("{\"kind\":\"Status\"}"));

        assertEquals(OptionalInt.empty(), client.getReplicas());
    }

    @Test
    @DisplayName("a scale object without spec.replicas is empty")
    void missingReplicasIsEmpty() {
        server.expect(requestTo(SCALE_URL))
                .andRespond(withSuccess("{\"kind\":\"Scale\"}", MediaType.APPLICATION_JSON));

        assertEquals(OptionalInt.empty(), client.getReplicas());
    }

    @Test
    @DisplayName("scales with a JSON merge patch of spec.replicas")
    void scalesWithMergePatch() {
        server.expect(requestTo(SCALE_URL))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header("Authorization", "Bearer sa-token"))
                .andExpect(header("Content-Type", "application/merge-patch+json"))
                .andExpect(content().json("{\"spec\":{\"replicas\":1}}"))
                .andRespond(withSuccess("{\"spec\":{\"replicas\":1}}", MediaType.APPLICATION_JSON));

        assertTrue(client.scaleTo(1));
        server.verify();
    }

    @Test
    @DisplayName("a rejected patch is false rather than an exception")
    void rejectedPatchIsFalse() {
        server.expect(requestTo(SCALE_URL))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("{\"kind\":\"Status\"}"));

        assertFalse(client.scaleTo(1));
    }

    @Test
    @DisplayName("sends no Authorization header when the token file is missing")
    void noTokenNoHeader() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer local = MockRestServiceServer.bindTo(restTemplate).build();
        KubernetesStatefulSetScaleClient tokenless = new KubernetesStatefulSetScaleClient(
                restTemplate, "https://10.96.0.1:443", "oak", "oak-omcsi-minecraft-wrapper", () -> null);
        local.expect(requestTo(SCALE_URL))
                .andExpect(request -> assertFalse(request.getHeaders().containsKey("Authorization")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertEquals(OptionalInt.empty(), tokenless.getReplicas());
    }
}
