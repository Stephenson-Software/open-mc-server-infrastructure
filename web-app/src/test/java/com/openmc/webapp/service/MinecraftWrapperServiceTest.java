package com.openmc.webapp.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MinecraftWrapperService Tests")
class MinecraftWrapperServiceTest {

    private static final String WRAPPER_URL = "http://test-wrapper:8092";
    private static final String START_URL = WRAPPER_URL + "/api/server/start";
    private static final String STOP_URL = WRAPPER_URL + "/api/server/stop";
    private static final String RESTART_URL = WRAPPER_URL + "/api/server/restart";
    private static final String UPLOAD_URL = WRAPPER_URL + "/api/world/upload";

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private RestTemplateBuilder restTemplateBuilder;

    @Captor
    private ArgumentCaptor<HttpEntity<?>> requestCaptor;

    private MinecraftWrapperService service;

    @BeforeEach
    void setUp() {
        when(restTemplateBuilder.setConnectTimeout(any())).thenReturn(restTemplateBuilder);
        when(restTemplateBuilder.setReadTimeout(any())).thenReturn(restTemplateBuilder);
        when(restTemplateBuilder.build()).thenReturn(restTemplate);

        service = new MinecraftWrapperService(restTemplateBuilder, 600L);
        ReflectionTestUtils.setField(service, "wrapperUrl", WRAPPER_URL);
    }

    private static HttpClientErrorException clientError(HttpStatus status, String body) {
        return HttpClientErrorException.create(
                status,
                status.getReasonPhrase(),
                HttpHeaders.EMPTY,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Should report success when wrapper accepts a start request")
    void shouldReportSuccessOnStartAccepted() {
        when(restTemplate.postForEntity(eq(START_URL), isNull(), eq(String.class)))
                .thenReturn(ResponseEntity.accepted().body("Server start initiated"));

        MinecraftWrapperService.WrapperResult result = service.startServer();

        assertTrue(result.success());
        assertEquals("Server start initiated", result.message());
    }

    @Test
    @DisplayName("Should surface the wrapper's 409 reason when the server is already running")
    void shouldSurfaceConflictReasonOnStart() {
        when(restTemplate.postForEntity(eq(START_URL), isNull(), eq(String.class)))
                .thenThrow(clientError(HttpStatus.CONFLICT, "Server is already running"));

        MinecraftWrapperService.WrapperResult result = service.startServer();

        assertFalse(result.success());
        assertEquals("Server is already running", result.message());
    }

    @Test
    @DisplayName("Should surface the wrapper's 409 reason when the server is not running")
    void shouldSurfaceConflictReasonOnStop() {
        when(restTemplate.postForEntity(eq(STOP_URL), isNull(), eq(String.class)))
                .thenThrow(clientError(HttpStatus.CONFLICT, "Server is not running"));

        MinecraftWrapperService.WrapperResult result = service.stopServer();

        assertFalse(result.success());
        assertEquals("Server is not running", result.message());
    }

    @Test
    @DisplayName("Should report success when wrapper accepts a restart request")
    void shouldReportSuccessOnRestartAccepted() {
        when(restTemplate.postForEntity(eq(RESTART_URL), isNull(), eq(String.class)))
                .thenReturn(ResponseEntity.accepted().body("Server restart initiated"));

        MinecraftWrapperService.WrapperResult result = service.restartServer();

        assertTrue(result.success());
        assertEquals("Server restart initiated", result.message());
    }

    @Test
    @DisplayName("Should distinguish an unreachable wrapper from a rejected request")
    void shouldReportUnreachableWrapper() {
        when(restTemplate.postForEntity(eq(START_URL), isNull(), eq(String.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        MinecraftWrapperService.WrapperResult result = service.startServer();

        assertFalse(result.success());
        assertEquals(MinecraftWrapperService.UNREACHABLE_MESSAGE, result.message());
    }

    @Test
    @DisplayName("Should fall back to the status code when a 4xx carries no body")
    void shouldFallBackWhenRejectionHasNoBody() {
        when(restTemplate.postForEntity(eq(STOP_URL), isNull(), eq(String.class)))
                .thenThrow(clientError(HttpStatus.CONFLICT, ""));

        MinecraftWrapperService.WrapperResult result = service.stopServer();

        assertFalse(result.success());
        assertEquals("Failed to stop the server (HTTP 409)", result.message());
    }

    @Test
    @DisplayName("Should not echo a framework-generated 5xx error body onto the dashboard")
    void shouldNotEchoServerErrorBody() {
        when(restTemplate.postForEntity(eq(RESTART_URL), isNull(), eq(String.class)))
                .thenThrow(HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Internal Server Error",
                        HttpHeaders.EMPTY,
                        "{\"timestamp\":\"2026-01-01\",\"status\":500,\"error\":\"Internal Server Error\"}"
                                .getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));

        MinecraftWrapperService.WrapperResult result = service.restartServer();

        assertFalse(result.success());
        assertEquals("Failed to restart the server (HTTP 500)", result.message());
    }

    @Test
    @DisplayName("Should not echo a JSON-shaped 4xx body onto the dashboard")
    void shouldNotEchoJsonClientErrorBody() {
        when(restTemplate.postForEntity(eq(START_URL), isNull(), eq(String.class)))
                .thenThrow(clientError(HttpStatus.BAD_REQUEST, "{\"error\":\"bad request\"}"));

        MinecraftWrapperService.WrapperResult result = service.startServer();

        assertFalse(result.success());
        assertEquals("Failed to start the server (HTTP 400)", result.message());
    }

    @Test
    @DisplayName("Should not echo an over-long 4xx body onto the dashboard")
    void shouldNotEchoOverlongClientErrorBody() {
        when(restTemplate.postForEntity(eq(START_URL), isNull(), eq(String.class)))
                .thenThrow(clientError(HttpStatus.BAD_REQUEST, "x".repeat(201)));

        MinecraftWrapperService.WrapperResult result = service.startServer();

        assertFalse(result.success());
        assertEquals("Failed to start the server (HTTP 400)", result.message());
    }

    @Test
    @DisplayName("Should treat a non-2xx response returned without an exception as a rejection")
    void shouldTreatNon2xxResponseAsRejection() {
        when(restTemplate.postForEntity(eq(START_URL), isNull(), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.CONFLICT).body("Server is already running"));

        MinecraftWrapperService.WrapperResult result = service.startServer();

        assertFalse(result.success());
        assertEquals("Server is already running", result.message());
    }

    @Test
    @DisplayName("Should report success when the wrapper accepts a world upload")
    void shouldReportSuccessOnWorldUpload() {
        when(restTemplate.postForEntity(eq(UPLOAD_URL), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("World uploaded successfully"));

        MinecraftWrapperService.WrapperResult result = service.uploadWorld(worldZip(), "secret-token");

        assertTrue(result.success());
        assertEquals("World uploaded successfully. Server is restarting.", result.message());
    }

    @Test
    @DisplayName("Should surface the wrapper's validation reason for a rejected world upload")
    void shouldSurfaceWorldUploadValidationReason() {
        when(restTemplate.postForEntity(eq(UPLOAD_URL), any(HttpEntity.class), eq(String.class)))
                .thenThrow(clientError(HttpStatus.BAD_REQUEST,
                        "Invalid request: archive does not contain a level.dat"));

        MinecraftWrapperService.WrapperResult result = service.uploadWorld(worldZip(), "secret-token");

        assertFalse(result.success());
        assertEquals("Invalid request: archive does not contain a level.dat", result.message());
    }

    @Test
    @DisplayName("Should surface an unauthorized world upload distinctly from an unreachable wrapper")
    void shouldSurfaceWorldUploadUnauthorized() {
        when(restTemplate.postForEntity(eq(UPLOAD_URL), any(HttpEntity.class), eq(String.class)))
                .thenThrow(clientError(HttpStatus.UNAUTHORIZED, "Unauthorized"));

        MinecraftWrapperService.WrapperResult result = service.uploadWorld(worldZip(), "wrong-token");

        assertFalse(result.success());
        assertEquals("Unauthorized", result.message());
    }

    @Test
    @DisplayName("Should report an unreachable wrapper for a world upload")
    void shouldReportUnreachableWrapperOnWorldUpload() {
        when(restTemplate.postForEntity(eq(UPLOAD_URL), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        MinecraftWrapperService.WrapperResult result = service.uploadWorld(worldZip(), "secret-token");

        assertFalse(result.success());
        assertEquals(MinecraftWrapperService.UNREACHABLE_MESSAGE, result.message());
    }

    @Test
    @DisplayName("Should send the deploy token as a Bearer header on world upload")
    void shouldSendBearerTokenOnWorldUpload() {
        when(restTemplate.postForEntity(eq(UPLOAD_URL), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("World uploaded successfully"));

        service.uploadWorld(worldZip(), "secret-token");

        verify(restTemplate).postForEntity(eq(UPLOAD_URL), requestCaptor.capture(), eq(String.class));
        assertEquals("Bearer secret-token",
                requestCaptor.getValue().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    }

    private static MockMultipartFile worldZip() {
        return new MockMultipartFile("file", "world.zip", "application/zip", "not-really-a-zip".getBytes(StandardCharsets.UTF_8));
    }
}
