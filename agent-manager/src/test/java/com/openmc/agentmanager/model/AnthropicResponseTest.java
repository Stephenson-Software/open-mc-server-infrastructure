package com.openmc.agentmanager.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AnthropicResponse Tests")
class AnthropicResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Should read the snake_case stop_reason key into stopReason")
    void shouldReadStopReasonFromSnakeCase() throws Exception {
        AnthropicResponse response = objectMapper.readValue(
                "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"stop_reason\":\"tool_use\"}",
                AnthropicResponse.class);

        assertEquals("msg_1", response.getId());
        assertEquals("message", response.getType());
        assertEquals("assistant", response.getRole());
        assertEquals("tool_use", response.getStopReason());
    }

    @Test
    @DisplayName("Should ignore top-level fields the model does not declare")
    void shouldIgnoreUnknownTopLevelFields() throws Exception {
        String body = "{\"id\":\"msg_2\",\"model\":\"claude-test-model\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5},\"stop_reason\":\"end_turn\"}";

        AnthropicResponse response = assertDoesNotThrow(() -> objectMapper.readValue(body, AnthropicResponse.class));

        assertEquals("msg_2", response.getId());
        assertEquals("end_turn", response.getStopReason());
    }

    @Test
    @DisplayName("Should read text content blocks")
    void shouldReadTextContentBlock() throws Exception {
        AnthropicResponse response = objectMapper.readValue(
                "{\"content\":[{\"type\":\"text\",\"text\":\"Server is online.\"}]}",
                AnthropicResponse.class);

        assertEquals(1, response.getContent().size());
        AnthropicResponse.ContentBlock block = response.getContent().get(0);
        assertEquals("text", block.getType());
        assertEquals("Server is online.", block.getText());
        assertNull(block.getId());
        assertNull(block.getName());
        assertNull(block.getInput());
    }

    @Test
    @DisplayName("Should read tool_use content blocks with their id, name and input")
    void shouldReadToolUseContentBlock() throws Exception {
        AnthropicResponse response = objectMapper.readValue(
                "{\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_789\",\"name\":\"get_server_diagnostics\","
                        + "\"input\":{\"limit\":50}}]}",
                AnthropicResponse.class);

        AnthropicResponse.ContentBlock block = response.getContent().get(0);
        assertEquals("tool_use", block.getType());
        assertEquals("toolu_789", block.getId());
        assertEquals("get_server_diagnostics", block.getName());
        assertEquals(Map.of("limit", 50), block.getInput());
    }

    @Test
    @DisplayName("Should ignore content-block fields the model does not declare")
    void shouldIgnoreUnknownContentBlockFields() throws Exception {
        String body = "{\"content\":[{\"type\":\"text\",\"text\":\"hi\",\"citations\":[]}]}";

        AnthropicResponse response = assertDoesNotThrow(() -> objectMapper.readValue(body, AnthropicResponse.class));

        assertEquals("hi", response.getContent().get(0).getText());
    }
}
