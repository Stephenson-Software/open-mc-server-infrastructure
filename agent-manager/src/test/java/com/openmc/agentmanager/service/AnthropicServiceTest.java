package com.openmc.agentmanager.service;

import com.openmc.agentmanager.model.AnthropicRequest;
import com.openmc.agentmanager.model.AnthropicResponse;
import com.openmc.agentmanager.model.ToolResult;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AnthropicService Tests")
class AnthropicServiceTest {

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private AnthropicService anthropicService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(anthropicService, "apiKey", "test-key");
        ReflectionTestUtils.setField(anthropicService, "apiUrl", "https://api.anthropic.com/v1/messages");
        ReflectionTestUtils.setField(anthropicService, "model", "claude-sonnet-4-20250514");
        ReflectionTestUtils.setField(anthropicService, "maxTokens", 1024);
    }

    /**
     * Stub the outbound call and capture the request body that {@code callApi} builds.
     */
    private AnthropicRequest captureRequest() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<AnthropicRequest>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), captor.capture(),
                eq(AnthropicResponse.class)))
                .thenReturn(ResponseEntity.ok(AnthropicResponse.builder().build()));

        anthropicService.sendMessage("is the server okay?");

        return captor.getValue().getBody();
    }

    @Test
    @DisplayName("Should send the configured model in the request body")
    void shouldSendConfiguredModel() {
        ReflectionTestUtils.setField(anthropicService, "model", "claude-test-model");

        assertEquals("claude-test-model", captureRequest().getModel());
    }

    @Test
    @DisplayName("Should send the configured max_tokens budget in the request body")
    void shouldSendConfiguredMaxTokens() {
        ReflectionTestUtils.setField(anthropicService, "maxTokens", 4096);

        assertEquals(4096, captureRequest().getMaxTokens());
    }

    @Test
    @DisplayName("Should send the default max_tokens budget when none is overridden")
    void shouldSendDefaultMaxTokens() {
        assertEquals(1024, captureRequest().getMaxTokens());
    }

    @Test
    @DisplayName("Should extract text content from response")
    void shouldExtractTextContentFromResponse() {
        AnthropicResponse response = AnthropicResponse.builder()
                .content(List.of(AnthropicResponse.ContentBlock.builder()
                        .type("text")
                        .text("Hello, I can help you manage the server.")
                        .build()))
                .build();

        String text = anthropicService.extractTextContent(response);

        assertEquals("Hello, I can help you manage the server.", text);
    }

    @Test
    @DisplayName("Should return empty string for null response")
    void shouldReturnEmptyStringForNullResponse() {
        assertEquals("", anthropicService.extractTextContent(null));
    }

    @Test
    @DisplayName("Should return empty string for response with no text blocks")
    void shouldReturnEmptyStringForNoTextBlocks() {
        AnthropicResponse response = AnthropicResponse.builder()
                .content(List.of(AnthropicResponse.ContentBlock.builder()
                        .type("tool_use")
                        .id("tool-1")
                        .name("start_server")
                        .build()))
                .build();

        assertEquals("", anthropicService.extractTextContent(response));
    }

    @Test
    @DisplayName("Should find tool_use block in response")
    void shouldFindToolUseBlock() {
        AnthropicResponse response = AnthropicResponse.builder()
                .content(List.of(
                        AnthropicResponse.ContentBlock.builder()
                                .type("text")
                                .text("I'll start the server.")
                                .build(),
                        AnthropicResponse.ContentBlock.builder()
                                .type("tool_use")
                                .id("tool-1")
                                .name("start_server")
                                .build()
                ))
                .build();

        AnthropicResponse.ContentBlock toolBlock = anthropicService.findToolUseBlock(response);

        assertNotNull(toolBlock);
        assertEquals("tool_use", toolBlock.getType());
        assertEquals("start_server", toolBlock.getName());
    }

    @Test
    @DisplayName("Should return null when no tool_use block found")
    void shouldReturnNullWhenNoToolUseBlock() {
        AnthropicResponse response = AnthropicResponse.builder()
                .content(List.of(AnthropicResponse.ContentBlock.builder()
                        .type("text")
                        .text("I can help you.")
                        .build()))
                .build();

        assertNull(anthropicService.findToolUseBlock(response));
    }

    @Test
    @DisplayName("Should return null for null response when finding tool_use block")
    void shouldReturnNullForNullResponseFindingToolUse() {
        assertNull(anthropicService.findToolUseBlock(null));
    }

    @Test
    @DisplayName("Should concatenate multiple text blocks with newlines")
    void shouldConcatenateMultipleTextBlocks() {
        AnthropicResponse response = AnthropicResponse.builder()
                .content(List.of(
                        AnthropicResponse.ContentBlock.builder()
                                .type("text")
                                .text("First block.")
                                .build(),
                        AnthropicResponse.ContentBlock.builder()
                                .type("tool_use")
                                .id("tool-1")
                                .name("start_server")
                                .build(),
                        AnthropicResponse.ContentBlock.builder()
                                .type("text")
                                .text("Second block.")
                                .build()
                ))
                .build();

        String text = anthropicService.extractTextContent(response);

        assertEquals("First block.\nSecond block.", text);
    }

    @Test
    @DisplayName("Should return empty string for response with null content list")
    void shouldReturnEmptyStringForNullContentList() {
        AnthropicResponse response = AnthropicResponse.builder()
                .content(null)
                .build();

        assertEquals("", anthropicService.extractTextContent(response));
    }

    @Test
    @DisplayName("Should return empty string for response with empty content list")
    void shouldReturnEmptyStringForEmptyContentList() {
        AnthropicResponse response = AnthropicResponse.builder()
                .content(Collections.emptyList())
                .build();

        assertEquals("", anthropicService.extractTextContent(response));
    }

    @Test
    @DisplayName("Should return null for findToolUseBlock with null content list")
    void shouldReturnNullForFindToolUseBlockWithNullContent() {
        AnthropicResponse response = AnthropicResponse.builder()
                .content(null)
                .build();

        assertNull(anthropicService.findToolUseBlock(response));
    }

    @Test
    @DisplayName("Should return null for findToolUseBlock with empty content list")
    void shouldReturnNullForFindToolUseBlockWithEmptyContent() {
        AnthropicResponse response = AnthropicResponse.builder()
                .content(Collections.emptyList())
                .build();

        assertNull(anthropicService.findToolUseBlock(response));
    }

    @Test
    @DisplayName("Should find first tool_use block when multiple exist")
    void shouldFindFirstToolUseBlockWhenMultipleExist() {
        AnthropicResponse response = AnthropicResponse.builder()
                .content(List.of(
                        AnthropicResponse.ContentBlock.builder()
                                .type("tool_use")
                                .id("tool-1")
                                .name("start_server")
                                .build(),
                        AnthropicResponse.ContentBlock.builder()
                                .type("tool_use")
                                .id("tool-2")
                                .name("stop_server")
                                .build()
                ))
                .build();

        AnthropicResponse.ContentBlock toolBlock = anthropicService.findToolUseBlock(response);

        assertNotNull(toolBlock);
        assertEquals("start_server", toolBlock.getName());
        assertEquals("tool-1", toolBlock.getId());
    }

    @Test
    @DisplayName("Should send the tool result as a typed ToolResultContent block in the final user message")
    void shouldSendToolResultAsTypedBlock() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<AnthropicRequest>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), captor.capture(),
                eq(AnthropicResponse.class)))
                .thenReturn(ResponseEntity.ok(AnthropicResponse.builder().build()));

        List<AnthropicResponse.ContentBlock> assistantContent = List.of(
                AnthropicResponse.ContentBlock.builder()
                        .type("tool_use")
                        .id("toolu_789")
                        .name("trigger_backup")
                        .build());
        ToolResult toolResult = ToolResult.builder()
                .toolUseId("toolu_789")
                .toolName("trigger_backup")
                .success(true)
                .message("Backup triggered")
                .build();

        anthropicService.sendToolResult("back up the world", assistantContent, toolResult);

        List<AnthropicRequest.Message> messages = captor.getValue().getBody().getMessages();
        assertEquals(3, messages.size());
        AnthropicRequest.Message toolResultMessage = messages.get(2);
        assertEquals("user", toolResultMessage.getRole());
        List<?> content = (List<?>) toolResultMessage.getContent();
        assertEquals(1, content.size());
        AnthropicRequest.ToolResultContent block =
                assertInstanceOf(AnthropicRequest.ToolResultContent.class, content.get(0));
        assertEquals("tool_result", block.getType());
        assertEquals("toolu_789", block.getToolUseId());
        assertEquals("Backup triggered", block.getContent());
    }
}
