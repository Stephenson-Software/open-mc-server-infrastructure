package com.openmc.minecraftwrapper.config;

import com.openmc.minecraftwrapper.service.AlertService;
import com.openmc.minecraftwrapper.service.MessageService;
import com.openmc.minecraftwrapper.service.WebAppNotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RestTemplateConfig Tests")
class RestTemplateConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RestTemplateAutoConfiguration.class))
            .withUserConfiguration(RestTemplateConfig.class, AlertService.class,
                    MessageService.class, WebAppNotificationService.class);

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
    @DisplayName("Should apply a 5s connect and 5s read timeout")
    void shouldApplyFixedTimeouts() {
        contextRunner.run(context -> {
            RestTemplate restTemplate = context.getBean(RestTemplate.class);

            assertEquals(5_000, connectTimeoutMillis(restTemplate));
            assertEquals(5_000, readTimeoutMillis(restTemplate));
        });
    }

    @Test
    @DisplayName("Should ignore http.client timeout properties, which only alert-manager and agent-manager read")
    void shouldIgnoreHttpClientTimeoutProperties() {
        contextRunner
                .withPropertyValues(
                        "http.client.connect-timeout-seconds=7",
                        "http.client.read-timeout-seconds=11")
                .run(context -> {
                    RestTemplate restTemplate = context.getBean(RestTemplate.class);

                    assertEquals(5_000, connectTimeoutMillis(restTemplate));
                    assertEquals(5_000, readTimeoutMillis(restTemplate));
                });
    }

    @Test
    @DisplayName("Should build from the Spring-managed RestTemplateBuilder so registered customizers apply")
    void shouldApplyRegisteredCustomizers() {
        ClientHttpRequestInterceptor interceptor = (request, body, execution) -> execution.execute(request, body);

        contextRunner
                .withBean(RestTemplateCustomizer.class, () -> restTemplate -> restTemplate.getInterceptors().add(interceptor))
                .run(context -> {
                    RestTemplate restTemplate = context.getBean(RestTemplate.class);

                    assertTrue(restTemplate.getInterceptors().contains(interceptor));
                });
    }

    @Test
    @DisplayName("Should inject the one configured client into every outbound-HTTP service")
    void shouldInjectClientIntoServices() {
        contextRunner.run(context -> {
            RestTemplate restTemplate = context.getBean(RestTemplate.class);

            assertSame(restTemplate, ReflectionTestUtils.getField(context.getBean(AlertService.class), "restTemplate"));
            assertSame(restTemplate, ReflectionTestUtils.getField(context.getBean(MessageService.class), "restTemplate"));
            assertSame(restTemplate,
                    ReflectionTestUtils.getField(context.getBean(WebAppNotificationService.class), "restTemplate"));
        });
    }
}
