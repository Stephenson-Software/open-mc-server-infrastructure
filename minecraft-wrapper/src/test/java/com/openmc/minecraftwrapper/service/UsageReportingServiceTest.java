package com.openmc.minecraftwrapper.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the startup report against a stub trace server on a loopback port (the JDK's own
 * HTTP server), so nothing here ever reaches the real service.
 */
class UsageReportingServiceTest {

    private HttpServer server;
    private String endpoint;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final CountDownLatch arrived = new CountDownLatch(1);

    @BeforeEach
    void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            bodies.add(readAll(exchange.getRequestBody()));
            paths.add(exchange.getRequestURI().getPath());
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
            arrived.countDown();
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    void startupEventCarriesProgramNameAndVersion() throws Exception {
        UsageReportingService service = new UsageReportingService(true, endpoint, "test-key", "", "9.9.9-TEST");
        assertTrue(service.isEnabled());

        service.reportStartup();

        assertTrue(arrived.await(5, TimeUnit.SECONDS), "startup event was not delivered");
        assertEquals(1, bodies.size());
        assertEquals("{\"application\":\"open-mc-server-infrastructure\",\"name\":\"startup\","
                + "\"tags\":{\"version\":\"9.9.9-TEST\"}}", bodies.get(0));
        assertEquals("/api/metrics", paths.get(0));
        assertEquals("Bearer test-key", authorizations.get(0));
        service.close();
    }

    @Test
    void configuredTagsAreAttachedAfterTheVersion() throws Exception {
        UsageReportingService service =
                new UsageReportingService(true, endpoint, "test-key", "ci=true, env = staging", "1.2.3");

        service.reportStartup();

        assertTrue(arrived.await(5, TimeUnit.SECONDS), "startup event was not delivered");
        assertEquals("{\"application\":\"open-mc-server-infrastructure\",\"name\":\"startup\","
                + "\"tags\":{\"version\":\"1.2.3\",\"ci\":\"true\",\"env\":\"staging\"}}", bodies.get(0));
        service.close();
    }

    @Test
    void startupTagsAreVersionThenConfiguredTags() {
        UsageReportingService service = new UsageReportingService(false, endpoint, "k", "ci=true", "1.2.3");
        assertEquals(Map.of("version", "1.2.3", "ci", "true"), service.startupTags());
        service.close();
    }

    @Test
    void parseTagsIgnoresMalformedEntriesAndTheReservedVersionKey() {
        assertEquals(Map.of(), UsageReportingService.parseTags(null));
        assertEquals(Map.of(), UsageReportingService.parseTags("  "));
        assertEquals(Map.of("ci", "true"), UsageReportingService.parseTags("ci=true"));
        assertEquals(Map.of("ci", "true", "region", "eu"),
                UsageReportingService.parseTags(" ci=true ,, novalue , =blank , region=eu ,"));
        assertEquals(Map.of("ci", "true"), UsageReportingService.parseTags("version=spoofed,ci=true"));
        assertEquals(Map.of("a", "b=c"), UsageReportingService.parseTags("a=b=c"));
        assertEquals(Map.of("empty", ""), UsageReportingService.parseTags("empty="));
    }

    @Test
    void versionIsNeverOverriddenByAConfiguredTag() {
        UsageReportingService service =
                new UsageReportingService(false, endpoint, "k", "version=spoofed", "1.2.3");
        assertEquals("1.2.3", service.startupTags().get("version"));
        service.close();
    }

    @Test
    void blankVersionIsReportedAsUnknown() {
        UsageReportingService service = new UsageReportingService(false, endpoint, "k", "", " ");
        assertEquals("unknown", service.startupTags().get("version"));
        service.close();
    }

    @Test
    void disabledSendsNothing() throws Exception {
        UsageReportingService service = new UsageReportingService(false, endpoint, "test-key", "", "1.0");
        assertFalse(service.isEnabled());

        service.reportStartup();

        assertFalse(arrived.await(300, TimeUnit.MILLISECONDS));
        assertTrue(bodies.isEmpty());
        service.close();
    }

    @Test
    void missingKeySendsNothing() throws Exception {
        UsageReportingService service = new UsageReportingService(true, endpoint, "", "", "1.0");
        assertFalse(service.isEnabled());

        service.reportStartup();

        assertFalse(arrived.await(300, TimeUnit.MILLISECONDS));
        assertTrue(bodies.isEmpty());
        service.close();
    }

    @Test
    void blankEndpointIsDisabledRatherThanFailing() {
        UsageReportingService service = assertDoesNotThrow(
                () -> new UsageReportingService(true, " ", "test-key", "", "1.0"));
        assertFalse(service.isEnabled());
        service.close();
    }

    @Test
    void unreachableServerNeverThrows() {
        // Nothing listens on this port once the stub is stopped; the report must be dropped quietly.
        server.stop(0);
        UsageReportingService service = new UsageReportingService(true, endpoint, "test-key", "", "1.0");
        assertDoesNotThrow(service::reportStartup);
        assertDoesNotThrow(service::close);
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
