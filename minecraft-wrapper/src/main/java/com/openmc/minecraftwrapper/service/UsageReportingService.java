package com.openmc.minecraftwrapper.service;

import com.openmc.minecraftwrapper.trace.TraceClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports that this OMCSI deployment started to the trace usage service
 * (https://github.com/Stephenson-Software/trace), so the project can see how many
 * deployments are running and on which version.
 *
 * <p>Exactly one event is sent, {@code startup}, once the wrapper is up. It carries the
 * program name ({@value #APPLICATION}), the wrapper's version and any operator-supplied
 * {@code USAGE_REPORTING_TAGS} — nothing per request, nothing per player, and nothing about
 * the world, the operator, the host or its address. The send happens on the client's own
 * daemon thread, never throws, and a trace server that is down or unreachable costs nothing
 * beyond a dropped report.
 *
 * <p>Configured through the {@code usage-reporting.*} properties in
 * {@code application.properties}, each backed by an environment variable:
 * {@code USAGE_REPORTING_ENABLED} (default {@code true}), {@code USAGE_REPORTING_ENDPOINT},
 * {@code USAGE_REPORTING_KEY} and {@code USAGE_REPORTING_TAGS} (comma-separated {@code k=v}
 * pairs attached to the event, e.g. {@code ci=true}). Reporting is on by default;
 * {@code USAGE_REPORTING_ENABLED=false} turns it off. Because other people deploy this
 * stack, one INFO line is logged on every start saying that reporting is on and how to
 * turn it off.
 */
@Service
public class UsageReportingService {

    private static final Logger log = LoggerFactory.getLogger(UsageReportingService.class);

    /** The {@code application} the program key was issued for. */
    static final String APPLICATION = "open-mc-server-infrastructure";
    static final String STARTUP_EVENT = "startup";
    static final String VERSION_TAG = "version";
    static final String UNKNOWN_VERSION = "unknown";

    private final TraceClient client;
    private final String version;
    private final Map<String, String> configuredTags;

    @Autowired
    public UsageReportingService(
            @Value("${usage-reporting.enabled:true}") boolean enabled,
            @Value("${usage-reporting.endpoint:https://trace.danielstephenson.dev}") String endpoint,
            @Value("${usage-reporting.key:}") String key,
            @Value("${usage-reporting.tags:}") String tags,
            ObjectProvider<BuildProperties> buildProperties) {
        this(enabled, endpoint, key, tags, versionOf(buildProperties.getIfAvailable()));
    }

    UsageReportingService(boolean enabled, String endpoint, String key, String tags, String version) {
        this.version = version == null || version.isBlank() ? UNKNOWN_VERSION : version.trim();
        this.configuredTags = parseTags(tags);
        this.client = buildClient(enabled, endpoint, key);
        if (client.isEnabled()) {
            log.info("Usage reporting is on: {} sends a startup event (program name and version only{}) to {}."
                    + " Turn it off with USAGE_REPORTING_ENABLED=false.",
                    APPLICATION,
                    configuredTags.isEmpty() ? "" : ", plus the configured USAGE_REPORTING_TAGS",
                    endpoint);
        } else {
            log.info("Usage reporting is off.");
        }
    }

    private static String versionOf(BuildProperties buildProperties) {
        return buildProperties == null ? UNKNOWN_VERSION : buildProperties.getVersion();
    }

    private static TraceClient buildClient(boolean enabled, String endpoint, String key) {
        if (!enabled || endpoint == null || endpoint.isBlank()) {
            return TraceClient.disabled();
        }
        return TraceClient.builder(endpoint, APPLICATION)
                .key(key)
                .enabled(true)
                .logger(java.util.logging.Logger.getLogger(UsageReportingService.class.getName()))
                .build();
    }

    /**
     * Parses {@code USAGE_REPORTING_TAGS}: comma-separated {@code key=value} pairs. Blank
     * entries, entries without {@code =}, entries with a blank key, and a {@code version}
     * entry (reserved for the wrapper's own version) are ignored rather than rejected, so a
     * typo in a tag can never keep the wrapper from starting.
     */
    static Map<String, String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String entry : tags.split(",")) {
            int separator = entry.indexOf('=');
            if (separator < 0) {
                continue;
            }
            String key = entry.substring(0, separator).trim();
            String value = entry.substring(separator + 1).trim();
            if (key.isEmpty() || VERSION_TAG.equals(key)) {
                continue;
            }
            parsed.put(key, value);
        }
        return Collections.unmodifiableMap(parsed);
    }

    /** Whether a startup report will actually be sent (false when disabled or without a key). */
    public boolean isEnabled() {
        return client.isEnabled();
    }

    /** The tags attached to the startup event: the wrapper version, then the configured tags. */
    Map<String, String> startupTags() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(VERSION_TAG, version);
        tags.putAll(configuredTags);
        return tags;
    }

    /** Sends the one {@code startup} event once the wrapper is up and serving requests. */
    @EventListener(ApplicationReadyEvent.class)
    public void reportStartup() {
        client.report(STARTUP_EVENT, null, startupTags());
    }

    @PreDestroy
    public void close() {
        client.close();
    }
}
