package com.openmc.agentmanager.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AnthropicRequest Tests")
class AnthropicRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static AnthropicRequest.Message userMessage(String text) {
        return AnthropicRequest.Message.builder().role("user").content(text).build();
    }

    @Test
    @DisplayName("Should serialize the token budget under the snake_case max_tokens key")
    void shouldSerializeMaxTokensAsSnakeCase() {
        AnthropicRequest request = AnthropicRequest.builder()
                .model("claude-test-model")
                .maxTokens(2048)
                .messages(List.of(userMessage("hi")))
                .build();

        JsonNode json = objectMapper.valueToTree(request);

        assertEquals(2048, json.get("max_tokens").asInt());
        assertFalse(json.has("maxTokens"));
    }

    @Test
    @DisplayName("Should serialize model, system, messages and tools under their API keys")
    void shouldSerializeTopLevelFields() {
        AnthropicRequest request = AnthropicRequest.builder()
                .model("claude-test-model")
                .system("You are a test assistant.")
                .maxTokens(1024)
                .messages(List.of(userMessage("status?")))
                .tools(List.of(ToolDefinition.startServer()))
                .build();

        JsonNode json = objectMapper.valueToTree(request);

        assertEquals("claude-test-model", json.get("model").asText());
        assertEquals("You are a test assistant.", json.get("system").asText());
        assertEquals("user", json.get("messages").get(0).get("role").asText());
        assertEquals("status?", json.get("messages").get(0).get("content").asText());
        assertEquals("start_server", json.get("tools").get(0).get("name").asText());
    }

    @Test
    @DisplayName("Should omit null system and tools fields from the request body")
    void shouldOmitNullFields() {
        AnthropicRequest request = AnthropicRequest.builder()
                .model("claude-test-model")
                .maxTokens(1024)
                .messages(List.of(userMessage("hi")))
                .build();

        JsonNode json = objectMapper.valueToTree(request);

        assertFalse(json.has("system"));
        assertFalse(json.has("tools"));
    }

    @Test
    @DisplayName("Should always send max_tokens, even when it was never set")
    void shouldAlwaysSendMaxTokens() {
        // max_tokens is a primitive int, so NON_NULL never drops it: an unset budget
        // goes out as 0 rather than being omitted.
        JsonNode json = objectMapper.valueToTree(AnthropicRequest.builder().model("m").build());

        assertTrue(json.has("max_tokens"));
        assertEquals(0, json.get("max_tokens").asInt());
    }

    @Test
    @DisplayName("Should serialize structured message content as a JSON array")
    void shouldSerializeStructuredContentAsArray() {
        AnthropicRequest.Message message = AnthropicRequest.Message.builder()
                .role("user")
                .content(List.of(Map.of(
                        "type", "tool_result",
                        "tool_use_id", "toolu_123",
                        "content", "Server started")))
                .build();

        JsonNode json = objectMapper.valueToTree(message);

        assertTrue(json.get("content").isArray());
        assertEquals("tool_result", json.get("content").get(0).get("type").asText());
        assertEquals("toolu_123", json.get("content").get(0).get("tool_use_id").asText());
    }

    @Test
    @DisplayName("Should omit a null content field from a message")
    void shouldOmitNullMessageContent() {
        JsonNode json = objectMapper.valueToTree(AnthropicRequest.Message.builder().role("user").build());

        assertEquals("user", json.get("role").asText());
        assertFalse(json.has("content"));
    }

    @Test
    @DisplayName("Should serialize the tool result id under the snake_case tool_use_id key")
    void shouldSerializeToolResultContentAsSnakeCase() {
        AnthropicRequest.ToolResultContent content = AnthropicRequest.ToolResultContent.builder()
                .type("tool_result")
                .toolUseId("toolu_456")
                .content("Backup triggered")
                .build();

        JsonNode json = objectMapper.valueToTree(content);

        assertEquals("tool_result", json.get("type").asText());
        assertEquals("toolu_456", json.get("tool_use_id").asText());
        assertFalse(json.has("toolUseId"));
        assertEquals("Backup triggered", json.get("content").asText());
    }
}
