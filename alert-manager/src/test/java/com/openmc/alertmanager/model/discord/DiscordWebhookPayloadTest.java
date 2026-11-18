package com.openmc.alertmanager.model.discord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks in the JSON that actually goes over the wire to Discord's Execute Webhook
 * endpoint. Discord ignores keys it does not recognise, so a field that serializes
 * under the wrong name fails silently rather than with an error.
 *
 * <p>The mapper mirrors the one the alert-manager's {@code RestTemplate} uses:
 * {@code new RestTemplate(factory)} registers a default
 * {@code MappingJackson2HttpMessageConverter}, which builds its mapper with
 * {@code Jackson2ObjectMapperBuilder.json().build()} and no naming strategy.
 */
@DisplayName("DiscordWebhookPayload Wire Format Tests")
class DiscordWebhookPayloadTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = Jackson2ObjectMapperBuilder.json().build();
    }

    private JsonNode toJson(Object value) {
        return objectMapper.valueToTree(value);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        Collections.sort(names);
        return names;
    }

    @Test
    @DisplayName("Should omit every unset top-level field")
    void shouldOmitUnsetTopLevelFields() {
        DiscordWebhookPayload payload = DiscordWebhookPayload.builder()
            .embeds(Collections.singletonList(DiscordEmbed.builder().title("t").build()))
            .build();

        JsonNode json = toJson(payload);

        assertEquals(List.of("embeds"), fieldNames(json));
    }

    @Test
    @DisplayName("Should serialize an empty payload as an empty object")
    void shouldSerializeEmptyPayloadAsEmptyObject() {
        JsonNode json = toJson(new DiscordWebhookPayload());

        assertTrue(json.isObject());
        assertEquals(0, json.size());
    }

    @Test
    @DisplayName("Should serialize top-level content, username and tts under their Discord names")
    void shouldSerializeTopLevelScalarFields() {
        DiscordWebhookPayload payload = DiscordWebhookPayload.builder()
            .content("hello")
            .username("OMCSI")
            .tts(false)
            .build();

        JsonNode json = toJson(payload);

        assertEquals("hello", json.get("content").asText());
        assertEquals("OMCSI", json.get("username").asText());
        assertTrue(json.get("tts").isBoolean());
        assertFalse(json.get("tts").asBoolean());
    }

    @Test
    @DisplayName("Should serialize avatarUrl under Discord's snake_case avatar_url key")
    void shouldSerializeAvatarUrlAsSnakeCase() {
        DiscordWebhookPayload payload = DiscordWebhookPayload.builder()
            .avatarUrl("https://example.com/avatar.png")
            .build();

        JsonNode json = toJson(payload);

        assertEquals("https://example.com/avatar.png", json.path("avatar_url").asText(null));
        assertFalse(json.has("avatarUrl"));
    }

    @Test
    @DisplayName("Should serialize the embed shape DiscordAlertService builds, and nothing else")
    void shouldSerializeServiceBuiltEmbedShape() {
        DiscordEmbed embed = DiscordEmbed.builder()
            .title("Server down")
            .description("RCON did not answer")
            .color(15158332)
            .timestamp("2026-11-15T12:00:00")
            .footer(DiscordEmbedFooter.builder().text("Source: minecraft-wrapper").build())
            .build();

        JsonNode json = toJson(embed);

        assertEquals(List.of("color", "description", "footer", "timestamp", "title"), fieldNames(json));
        assertEquals("Server down", json.get("title").asText());
        assertEquals("RCON did not answer", json.get("description").asText());
        assertTrue(json.get("color").isInt(), "Discord requires color as an integer, not a string");
        assertEquals(15158332, json.get("color").asInt());
        assertEquals("2026-11-15T12:00:00", json.get("timestamp").asText());
        assertEquals(List.of("text"), fieldNames(json.get("footer")));
        assertEquals("Source: minecraft-wrapper", json.get("footer").get("text").asText());
    }

    @Test
    @DisplayName("Should serialize footer iconUrl as icon_url")
    void shouldSerializeFooterIconUrlAsSnakeCase() {
        DiscordEmbedFooter footer = DiscordEmbedFooter.builder()
            .text("footer")
            .iconUrl("https://example.com/f.png")
            .build();

        JsonNode json = toJson(footer);

        assertEquals(List.of("icon_url", "text"), fieldNames(json));
        assertEquals("https://example.com/f.png", json.get("icon_url").asText());
    }

    @Test
    @DisplayName("Should serialize author iconUrl as icon_url")
    void shouldSerializeAuthorIconUrlAsSnakeCase() {
        DiscordEmbedAuthor author = DiscordEmbedAuthor.builder()
            .name("OMCSI")
            .url("https://example.com")
            .iconUrl("https://example.com/a.png")
            .build();

        JsonNode json = toJson(author);

        assertEquals(List.of("icon_url", "name", "url"), fieldNames(json));
        assertEquals("https://example.com/a.png", json.get("icon_url").asText());
    }

    @Test
    @DisplayName("Should serialize embed fields with name, value and inline")
    void shouldSerializeEmbedFields() {
        DiscordEmbed embed = DiscordEmbed.builder()
            .fields(List.of(
                DiscordEmbedField.builder().name("Players").value("3").inline(true).build(),
                DiscordEmbedField.builder().name("TPS").value("20.0").build()))
            .build();

        JsonNode fields = toJson(embed).get("fields");

        assertTrue(fields.isArray());
        assertEquals(2, fields.size());
        assertEquals(List.of("inline", "name", "value"), fieldNames(fields.get(0)));
        assertTrue(fields.get(0).get("inline").asBoolean());
        assertEquals(List.of("name", "value"), fieldNames(fields.get(1)),
            "an unset inline must be omitted, not sent as null");
    }

    @Test
    @DisplayName("Should serialize image and thumbnail with url, height and width")
    void shouldSerializeImageAndThumbnail() {
        DiscordEmbed embed = DiscordEmbed.builder()
            .image(DiscordEmbedImage.builder().url("https://example.com/i.png").height(10).width(20).build())
            .thumbnail(DiscordEmbedThumbnail.builder().url("https://example.com/t.png").build())
            .build();

        JsonNode json = toJson(embed);

        assertEquals(List.of("height", "url", "width"), fieldNames(json.get("image")));
        assertEquals(10, json.get("image").get("height").asInt());
        assertEquals(20, json.get("image").get("width").asInt());
        assertEquals(List.of("url"), fieldNames(json.get("thumbnail")));
    }
}
