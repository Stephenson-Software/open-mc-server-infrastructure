package com.openmc.agentmanager.config;

import com.openmc.agentmanager.service.BackupManagerService;
import com.openmc.agentmanager.service.MinecraftWrapperService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RestTemplateConfig Tests")
class RestTemplateConfigTest {

    private static AnnotationConfigApplicationContext contextWith(Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("test", properties));
        context.register(RestTemplateConfig.class, BackupManagerService.class, MinecraftWrapperService.class);
        context.refresh();
        return context;
    }

    private static SimpleClientHttpRequestFactory factoryOf(RestTemplate restTemplate) {
        return assertInstanceOf(SimpleClientHttpRequestFactory.class, restTemplate.getRequestFactory());
    }

    private static int connectTimeoutMillis(RestTemplate restTemplate) {
        return (int) ReflectionTestUtils.getField(factoryOf(restTemplate), "connectTimeout");
    }

    private static int readTimeoutMillis(RestTemplate restTemplate) {
        return (int) ReflectionTestUtils.getField(factoryOf(restTemplate), "readTimeout");
    }

    @Test
    @DisplayName("Should default to a 5s connect and 30s read timeout on the primary client")
    void shouldApplyDefaultTimeoutsToPrimaryClient() {
        try (AnnotationConfigApplicationContext context = contextWith(Map.of())) {
            RestTemplate restTemplate = context.getBean("restTemplate", RestTemplate.class);

            assertEquals(5_000, connectTimeoutMillis(restTemplate));
            assertEquals(30_000, readTimeoutMillis(restTemplate));
        }
    }

    @Test
    @DisplayName("Should default to a 600s read timeout on the backup client, sharing the connect timeout")
    void shouldApplyDefaultTimeoutsToBackupClient() {
        try (AnnotationConfigApplicationContext context = contextWith(Map.of())) {
            RestTemplate backupRestTemplate = context.getBean("backupRestTemplate", RestTemplate.class);

            assertEquals(5_000, connectTimeoutMillis(backupRestTemplate));
            assertEquals(600_000, readTimeoutMillis(backupRestTemplate));
        }
    }

    @Test
    @DisplayName("Should apply configured timeout properties to each client")
    void shouldApplyConfiguredTimeouts() {
        Map<String, Object> properties = Map.of(
                "http.client.connect-timeout-seconds", "7",
                "http.client.read-timeout-seconds", "11",
                "backup.manager.read-timeout-seconds", "1200");

        try (AnnotationConfigApplicationContext context = contextWith(properties)) {
            RestTemplate restTemplate = context.getBean("restTemplate", RestTemplate.class);
            RestTemplate backupRestTemplate = context.getBean("backupRestTemplate", RestTemplate.class);

            assertEquals(7_000, connectTimeoutMillis(restTemplate));
            assertEquals(11_000, readTimeoutMillis(restTemplate));
            assertEquals(7_000, connectTimeoutMillis(backupRestTemplate));
            assertEquals(1_200_000, readTimeoutMillis(backupRestTemplate));
        }
    }

    @Test
    @DisplayName("Should resolve an unqualified RestTemplate to the primary client")
    void shouldResolveUnqualifiedInjectionToPrimaryClient() {
        try (AnnotationConfigApplicationContext context = contextWith(Map.of())) {
            RestTemplate primary = context.getBean("restTemplate", RestTemplate.class);
            MinecraftWrapperService wrapperService = context.getBean(MinecraftWrapperService.class);

            assertSame(primary, context.getBean(RestTemplate.class));
            assertSame(primary, ReflectionTestUtils.getField(wrapperService, "restTemplate"));
        }
    }

    @Test
    @DisplayName("Should inject the long-timeout backup client into BackupManagerService")
    void shouldInjectBackupClientIntoBackupManagerService() {
        try (AnnotationConfigApplicationContext context = contextWith(Map.of())) {
            RestTemplate backupRestTemplate = context.getBean("backupRestTemplate", RestTemplate.class);
            BackupManagerService backupManagerService = context.getBean(BackupManagerService.class);

            assertSame(backupRestTemplate, ReflectionTestUtils.getField(backupManagerService, "restTemplate"));
            assertNotSame(context.getBean("restTemplate"), backupRestTemplate);
        }
    }
}
