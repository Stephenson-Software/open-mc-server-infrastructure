package com.openmc.minecraftwrapper.trace;

import com.openmc.minecraftwrapper.service.UsageReportingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The wrapper decides {@code USAGE_REPORTING_ENABLED} itself, but the environment variables
 * every trace client honours must still win over it. Lives in the client's package so it
 * can point the client's environment seam at a map instead of the real environment.
 */
class UsageReportingEnvironmentTest {

    private final Map<String, String> environment = new HashMap<>();
    private Function<String, String> realEnvironment;

    @BeforeEach
    void isolateEnvironment() {
        realEnvironment = TraceClient.environment;
        TraceClient.environment = environment::get;
    }

    @AfterEach
    void restoreEnvironment() {
        TraceClient.environment = realEnvironment;
    }

    @Test
    void doNotTrackDisablesEvenWhenTheWrapperSaysEnabled() {
        environment.put("DO_NOT_TRACK", "1");

        UsageReportingService service = newService();

        assertFalse(service.isEnabled(), "DO_NOT_TRACK=1 must switch reporting off");
        service.close();
    }

    @Test
    void traceUsageReportingOffDisablesEvenWhenTheWrapperSaysEnabled() {
        environment.put("TRACE_USAGE_REPORTING", "off");

        UsageReportingService service = newService();

        assertFalse(service.isEnabled(), "TRACE_USAGE_REPORTING=off must switch reporting off");
        service.close();
    }

    @Test
    void withoutEitherVariableTheWrapperSettingStands() {
        UsageReportingService service = newService();

        assertTrue(service.isEnabled());
        service.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "FALSE", "off", "no", "0", "", " ", "disabled", "flase", "\"false\""})
    void usageReportingEnabledFailsClosed(String value) {
        // Spring's own boolean conversion refused to start on a typo or a blank value; the
        // switch is now read through the client's helper, so anything but a clear "on" is off.
        UsageReportingService service = newService(value);

        assertFalse(service.isEnabled(), "USAGE_REPORTING_ENABLED=" + value + " must switch reporting off");
        service.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "on", "yes", "1", " true "})
    void usageReportingEnabledOnValuesKeepItOn(String value) {
        UsageReportingService service = newService(value);

        assertTrue(service.isEnabled(), "USAGE_REPORTING_ENABLED=" + value + " keeps reporting on");
        service.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "disabled", "0"})
    void traceUsageReportingFailsClosed(String value) {
        environment.put("TRACE_USAGE_REPORTING", value);

        UsageReportingService service = newService();

        assertFalse(service.isEnabled(), "TRACE_USAGE_REPORTING=" + value + " must switch reporting off");
        service.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "yes", "anything"})
    void doNotTrackFailsClosed(String value) {
        environment.put("DO_NOT_TRACK", value);

        UsageReportingService service = newService();

        assertFalse(service.isEnabled(), "DO_NOT_TRACK=" + value + " must switch reporting off");
        service.close();
    }

    private static UsageReportingService newService() {
        return newService("true");
    }

    @SuppressWarnings("unchecked")
    private static UsageReportingService newService(String enabled) {
        ObjectProvider<BuildProperties> noBuildProperties = mock(ObjectProvider.class);
        // With a key, pointed at a port nothing listens on: nothing is sent either way. Goes
        // through the constructor Spring uses, so the raw USAGE_REPORTING_ENABLED string is read.
        return new UsageReportingService(enabled, "http://127.0.0.1:9", "test-key", "", noBuildProperties);
    }
}
