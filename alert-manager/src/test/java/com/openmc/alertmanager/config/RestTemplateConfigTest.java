package com.openmc.alertmanager.config;

import com.openmc.alertmanager.service.DiscordAlertService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RestTemplateConfig Tests")
class RestTemplateConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RestTemplateConfig.class, DiscordAlertService.class);

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
    @DisplayName("Should default to a 5s connect and 5s read timeout when the properties are unset")
    void shouldApplyDefaultTimeouts() {
        contextRunner.run(context -> {
            RestTemplate restTemplate = context.getBean(RestTemplate.class);

            assertEquals(5_000, connectTimeoutMillis(restTemplate));
            assertEquals(5_000, readTimeoutMillis(restTemplate));
        });
    }

    @Test
    @DisplayName("Should apply the configured http.client timeout properties")
    void shouldApplyConfiguredTimeouts() {
        contextRunner
                .withPropertyValues(
                        "http.client.connect-timeout-seconds=7",
                        "http.client.read-timeout-seconds=11")
                .run(context -> {
                    RestTemplate restTemplate = context.getBean(RestTemplate.class);

                    assertEquals(7_000, connectTimeoutMillis(restTemplate));
                    assertEquals(11_000, readTimeoutMillis(restTemplate));
                });
    }

    @Test
    @DisplayName("Should configure each timeout independently of the other")
    void shouldConfigureTimeoutsIndependently() {
        contextRunner
                .withPropertyValues("http.client.read-timeout-seconds=20")
                .run(context -> {
                    RestTemplate restTemplate = context.getBean(RestTemplate.class);

                    assertEquals(5_000, connectTimeoutMillis(restTemplate));
                    assertEquals(20_000, readTimeoutMillis(restTemplate));
                });
    }

    @Test
    @DisplayName("Should inject the configured client into DiscordAlertService")
    void shouldInjectClientIntoDiscordAlertService() {
        contextRunner.run(context -> {
            RestTemplate restTemplate = context.getBean(RestTemplate.class);
            DiscordAlertService discordAlertService = context.getBean(DiscordAlertService.class);

            assertSame(restTemplate, ReflectionTestUtils.getField(discordAlertService, "restTemplate"));
        });
    }
}
