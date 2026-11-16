package com.openmc.alertmanager.service;

import com.openmc.alertmanager.exception.AlertException;
import com.openmc.alertmanager.model.Alert;
import com.openmc.alertmanager.model.AlertLevel;
import com.openmc.alertmanager.model.discord.DiscordEmbed;
import com.openmc.alertmanager.model.discord.DiscordWebhookPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Exercises the enabled send path of {@link DiscordAlertService} — payload construction,
 * the level-to-colour mapping, and error wrapping — against a mocked {@link RestTemplate}.
 * {@link DiscordAlertServiceTest} covers only the disabled / unconfigured paths.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DiscordAlertService Webhook Tests")
class DiscordAlertServiceWebhookTest {

    private static final String WEBHOOK_URL = "https://discord.example.invalid/api/webhooks/1/token";

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private DiscordAlertService discordAlertService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(discordAlertService, "enabled", true);
        ReflectionTestUtils.setField(discordAlertService, "webhookUrl", WEBHOOK_URL);
    }

    private static Alert alert(AlertLevel level, String source) {
        return Alert.builder()
            .title("Server down")
            .message("RCON did not answer")
            .level(level)
            .source(source)
            .build();
    }

    private void stubResponse(HttpStatus status) {
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>("", status));
    }

    @SuppressWarnings("unchecked")
    private HttpEntity<DiscordWebhookPayload> captureSentEntity() {
        ArgumentCaptor<HttpEntity<DiscordWebhookPayload>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq(WEBHOOK_URL), eq(HttpMethod.POST), captor.capture(), eq(String.class));
        return captor.getValue();
    }

    @Test
    @DisplayName("Should POST one embed built from the alert to the configured webhook URL as JSON")
    void shouldPostSingleEmbedBuiltFromAlert() throws AlertException {
        stubResponse(HttpStatus.NO_CONTENT);

        discordAlertService.sendAlert(alert(AlertLevel.ERROR, "minecraft-wrapper"));

        HttpEntity<DiscordWebhookPayload> entity = captureSentEntity();
        assertEquals(MediaType.APPLICATION_JSON, entity.getHeaders().getContentType());

        DiscordWebhookPayload payload = entity.getBody();
        assertNotNull(payload);
        assertNull(payload.getContent());
        assertNull(payload.getUsername());
        assertNull(payload.getAvatarUrl());
        assertNull(payload.getTts());
        assertEquals(1, payload.getEmbeds().size());

        DiscordEmbed embed = payload.getEmbeds().get(0);
        assertEquals("Server down", embed.getTitle());
        assertEquals("RCON did not answer", embed.getDescription());
        assertEquals("Source: minecraft-wrapper", embed.getFooter().getText());
        assertNull(embed.getFooter().getIconUrl());
        assertNull(embed.getUrl());
        assertNull(embed.getFields());
        assertNull(embed.getAuthor());
    }

    @Test
    @DisplayName("Should map each alert level to its Discord embed colour")
    void shouldMapEachLevelToItsColour() throws AlertException {
        stubResponse(HttpStatus.NO_CONTENT);
        Map<AlertLevel, Integer> expected = new EnumMap<>(AlertLevel.class);
        expected.put(AlertLevel.INFO, 3447003);
        expected.put(AlertLevel.WARNING, 16776960);
        expected.put(AlertLevel.ERROR, 15158332);
        expected.put(AlertLevel.CRITICAL, 10038562);
        assertEquals(AlertLevel.values().length, expected.size(), "a new AlertLevel needs a colour here");

        for (Map.Entry<AlertLevel, Integer> entry : expected.entrySet()) {
            clearInvocations(restTemplate);
            discordAlertService.sendAlert(alert(entry.getKey(), "test"));
            DiscordEmbed embed = captureSentEntity().getBody().getEmbeds().get(0);
            assertEquals(entry.getValue(), embed.getColor(), "colour for " + entry.getKey());
        }
    }

    @Test
    @DisplayName("Should label a null source as unknown in the footer")
    void shouldLabelNullSourceAsUnknown() throws AlertException {
        stubResponse(HttpStatus.OK);

        discordAlertService.sendAlert(alert(AlertLevel.INFO, null));

        assertEquals("Source: unknown", captureSentEntity().getBody().getEmbeds().get(0).getFooter().getText());
    }

    @Test
    @DisplayName("Should stamp the embed with a parseable ISO local date-time")
    void shouldStampEmbedWithIsoTimestamp() throws AlertException {
        stubResponse(HttpStatus.OK);
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        discordAlertService.sendAlert(alert(AlertLevel.INFO, "test"));

        LocalDateTime after = LocalDateTime.now().plusSeconds(1);
        String timestamp = captureSentEntity().getBody().getEmbeds().get(0).getTimestamp();
        // Current behaviour: a zone-less LocalDateTime in the JVM's local time zone.
        LocalDateTime parsed = LocalDateTime.parse(timestamp, DateTimeFormatter.ISO_DATE_TIME);
        assertFalse(parsed.isBefore(before));
        assertFalse(parsed.isAfter(after));
    }

    @Test
    @DisplayName("Should wrap a non-2xx webhook response in an AlertException")
    void shouldWrapNon2xxResponse() {
        stubResponse(HttpStatus.MULTIPLE_CHOICES);

        AlertException thrown = assertThrows(AlertException.class,
            () -> discordAlertService.sendAlert(alert(AlertLevel.WARNING, "test")));

        assertEquals("Failed to send Discord alert", thrown.getMessage());
        assertInstanceOf(AlertException.class, thrown.getCause());
        assertTrue(thrown.getCause().getMessage().contains("non-success status"));
    }

    @Test
    @DisplayName("Should wrap a transport failure in an AlertException")
    void shouldWrapTransportFailure() {
        ResourceAccessException failure = new ResourceAccessException("connect timed out");
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
            .thenThrow(failure);

        AlertException thrown = assertThrows(AlertException.class,
            () -> discordAlertService.sendAlert(alert(AlertLevel.CRITICAL, "test")));

        assertEquals("Failed to send Discord alert", thrown.getMessage());
        assertSame(failure, thrown.getCause());
    }

    @Test
    @DisplayName("Should fail with an AlertException, without calling Discord, when the level is null")
    void shouldFailWithoutCallingDiscordWhenLevelIsNull() {
        // Current behaviour: the level switch throws before any request is made, and the
        // catch-all in sendAlert wraps it. Characterised here, not endorsed.
        AlertException thrown = assertThrows(AlertException.class,
            () -> discordAlertService.sendAlert(alert(null, "test")));

        assertEquals("Failed to send Discord alert", thrown.getMessage());
        assertInstanceOf(NullPointerException.class, thrown.getCause());
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("Should not call Discord when disabled, even with a webhook URL configured")
    void shouldNotCallDiscordWhenDisabled() throws AlertException {
        ReflectionTestUtils.setField(discordAlertService, "enabled", false);

        discordAlertService.sendAlert(alert(AlertLevel.CRITICAL, "test"));

        verifyNoInteractions(restTemplate);
    }
}
