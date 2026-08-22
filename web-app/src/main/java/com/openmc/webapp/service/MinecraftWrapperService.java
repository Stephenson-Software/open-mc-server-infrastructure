package com.openmc.webapp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for interacting with the Minecraft Wrapper REST API
 */
@Service
@ConditionalOnProperty(name = "minecraft.wrapper.enabled", havingValue = "true", matchIfMissing = true)
public class MinecraftWrapperService {

    private static final Logger log = LoggerFactory.getLogger(MinecraftWrapperService.class);

    /**
     * Shown when the wrapper never answered, as opposed to answering with a rejection.
     */
    static final String UNREACHABLE_MESSAGE = "Could not reach the Minecraft wrapper";

    /**
     * Longest wrapper response body echoed onto the dashboard; anything longer is replaced
     * with a generic message rather than dumping a wall of text into the admin UI.
     */
    private static final int MAX_SURFACED_MESSAGE_LENGTH = 200;

    @Value("${minecraft.wrapper.url:http://minecraft-wrapper:8092}")
    private String wrapperUrl;

    private final RestTemplate restTemplate;
    private final RestTemplate uploadRestTemplate;

    public MinecraftWrapperService(
            RestTemplateBuilder restTemplateBuilder,
            @Value("${world.upload.read-timeout-seconds:600}") long uploadReadTimeoutSeconds) {
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
        // The wrapper does not respond until it has stopped the server, extracted the
        // archive, swapped the world in and restarted, so this covers the whole operation
        // rather than just the transfer. It scales with world size — raise
        // WORLD_UPLOAD_READ_TIMEOUT_SECONDS alongside the upload size limits.
        this.uploadRestTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(uploadReadTimeoutSeconds))
                .build();
    }

    /**
     * Get the current server status from the wrapper
     * @return Server status information, or null if not available
     */
    public ServerStatus getServerStatus() {
        try {
            String url = wrapperUrl + "/api/server/status";
            log.debug("Fetching server status from: {}", url);
            
            ResponseEntity<ServerStatus> response = restTemplate.getForEntity(url, ServerStatus.class);
            return response.getBody();
        } catch (Exception e) {
            log.warn("Failed to get server status from wrapper: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Send a command to the Minecraft server via the wrapper
     * @param command Command to send
     * @return true if command was sent successfully
     */
    public boolean sendCommand(String command) {
        try {
            String url = wrapperUrl + "/api/server/command";
            log.info("Sending command to server via wrapper: {}", command);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_PLAIN);
            HttpEntity<String> request = new HttpEntity<>(command, headers);
            
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.error("Failed to send command to wrapper: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Send a message to players via the wrapper
     * @param text Message text
     * @param destination Destination (MINECRAFT or DISCORD)
     * @return true if message was sent successfully
     */
    public boolean sendMessage(String text, String destination) {
        try {
            String url = wrapperUrl + "/api/messages";
            log.info("Sending message via wrapper: {}", text);
            
            Map<String, String> messageRequest = new HashMap<>();
            messageRequest.put("text", text);
            messageRequest.put("destination", destination);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> request = new HttpEntity<>(messageRequest, headers);
            
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.error("Failed to send message via wrapper: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Start the Minecraft server via the wrapper.
     * The start endpoint returns immediately (202 Accepted) and performs start asynchronously.
     * It answers 409 Conflict when the server is already running.
     * @return the outcome, carrying the wrapper's own reason when it rejected the request
     */
    public WrapperResult startServer() {
        log.info("Starting server via wrapper");
        return post("/api/server/start", "start", "Server start initiated");
    }

    /**
     * Stop the Minecraft server via the wrapper.
     * The stop endpoint returns immediately (202 Accepted) and performs stop asynchronously.
     * It answers 409 Conflict when the server is not running.
     * @return the outcome, carrying the wrapper's own reason when it rejected the request
     */
    public WrapperResult stopServer() {
        log.info("Stopping server via wrapper");
        return post("/api/server/stop", "stop", "Server stop initiated");
    }

    /**
     * Restart the Minecraft server via the wrapper.
     * The restart endpoint returns immediately (202 Accepted) and performs restart asynchronously.
     * @return the outcome, carrying the wrapper's own reason when it rejected the request
     */
    public WrapperResult restartServer() {
        log.info("Restarting server via wrapper");
        return post("/api/server/restart", "restart", "Server restart initiated");
    }

    /**
     * Initiate graceful server shutdown via the wrapper.
     * The shutdown endpoint returns immediately (202 Accepted) and performs shutdown asynchronously.
     * @return the outcome, carrying the wrapper's own reason when it rejected the request
     */
    public WrapperResult initiateShutdown() {
        log.info("Initiating server shutdown via wrapper");
        return post("/api/server/shutdown", "shut down", "Server shutdown initiated");
    }

    /**
     * Upload a world ZIP archive to the wrapper, which stops the server, replaces the world,
     * and restarts the server.
     *
     * @param file            the ZIP archive containing the world
     * @param deployAuthToken the Bearer token for the wrapper's deploy endpoint
     * @return the outcome, carrying the wrapper's own reason when it rejected the upload
     *         (e.g. "Invalid request: ..." for a malformed archive)
     */
    public WrapperResult uploadWorld(MultipartFile file, String deployAuthToken) {
        String url = wrapperUrl + "/api/world/upload";
        try {
            log.info("Uploading world to wrapper");

            String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "world.zip";
            long size = file.getSize();
            Resource resource = new InputStreamResource(file.getInputStream()) {
                @Override
                public String getFilename() {
                    return filename;
                }

                @Override
                public long contentLength() {
                    return size;
                }
            };

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", resource);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.set("Authorization", "Bearer " + deployAuthToken);

            HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = uploadRestTemplate.postForEntity(url, request, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                return WrapperResult.success("World uploaded successfully. Server is restarting.");
            }
            return WrapperResult.failure(describeRejection(
                    response.getStatusCode().value(), response.getBody(), "upload the world"));
        } catch (HttpStatusCodeException e) {
            log.warn("Wrapper rejected world upload: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            return WrapperResult.failure(describeRejection(
                    e.getStatusCode().value(), e.getResponseBodyAsString(), "upload the world"));
        } catch (RestClientException e) {
            log.error("Failed to upload world to wrapper at {}: {}", url, e.getMessage());
            return WrapperResult.failure(UNREACHABLE_MESSAGE);
        } catch (Exception e) {
            // e.g. the multipart file could not be read back off disk
            log.error("Failed to upload world to wrapper at {}: {}", url, e.getMessage(), e);
            return WrapperResult.failure("World upload failed. Check server logs for details.");
        }
    }

    /**
     * Check if the wrapper service is available
     * @return true if wrapper is reachable
     */
    public boolean isAvailable() {
        return getServerStatus() != null;
    }

    /**
     * POST to a wrapper endpoint that takes no body, preserving the wrapper's own
     * explanation when it answers with an error status (e.g. 409 "Server is already running").
     *
     * @param path           wrapper path to POST to
     * @param action         verb used in log messages and in the generic fallback message
     * @param successMessage message shown to the admin when the wrapper accepted the request
     */
    private WrapperResult post(String path, String action, String successMessage) {
        String url = wrapperUrl + path;
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, null, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                return WrapperResult.success(successMessage);
            }
            return WrapperResult.failure(describeRejection(
                    response.getStatusCode().value(), response.getBody(), action + " the server"));
        } catch (HttpStatusCodeException e) {
            log.warn("Wrapper rejected {} request: {} - {}", action, e.getStatusCode(), e.getResponseBodyAsString());
            return WrapperResult.failure(describeRejection(
                    e.getStatusCode().value(), e.getResponseBodyAsString(), action + " the server"));
        } catch (RestClientException e) {
            log.error("Failed to {} server via wrapper at {}: {}", action, url, e.getMessage());
            return WrapperResult.failure(UNREACHABLE_MESSAGE);
        } catch (Exception e) {
            log.error("Unexpected failure calling wrapper at {}: {}", url, e.getMessage(), e);
            return WrapperResult.failure(String.format("Failed to %s the server", action));
        }
    }

    /**
     * Turn a wrapper error response into a message worth showing on the dashboard.
     *
     * <p>Only 4xx bodies are surfaced verbatim: those are the wrapper's deliberate,
     * human-readable rejections (409 "Server is already running", 400 "Invalid request: ...").
     * A 5xx body may be a framework-generated JSON or HTML error page, which would be noise
     * on the dashboard, so those fall back to a generic message.
     */
    private static String describeRejection(int statusCode, String responseBody, String actionPhrase) {
        boolean clientError = statusCode >= 400 && statusCode < 500;
        if (clientError && responseBody != null && !responseBody.isBlank()) {
            String trimmed = responseBody.trim();
            boolean looksLikeMarkup = trimmed.startsWith("{") || trimmed.startsWith("<") || trimmed.startsWith("[");
            if (!looksLikeMarkup && trimmed.length() <= MAX_SURFACED_MESSAGE_LENGTH) {
                return trimmed;
            }
        }
        return String.format("Failed to %s (HTTP %d)", actionPhrase, statusCode);
    }

    /**
     * Outcome of a call to the wrapper.
     *
     * @param success whether the wrapper accepted the request
     * @param message text suitable for display on the admin dashboard — the wrapper's own
     *                explanation when it answered, or a connectivity message when it did not
     */
    public record WrapperResult(boolean success, String message) {

        public static WrapperResult success(String message) {
            return new WrapperResult(true, message);
        }

        public static WrapperResult failure(String message) {
            return new WrapperResult(false, message);
        }
    }

    /**
     * Server status data class
     */
    public static class ServerStatus {
        private boolean running;
        private Long pid;
        private String serverJar;
        private String serverDirectory;

        public ServerStatus() {
        }

        public boolean isRunning() {
            return running;
        }

        public void setRunning(boolean running) {
            this.running = running;
        }

        public Long getPid() {
            return pid;
        }

        public void setPid(Long pid) {
            this.pid = pid;
        }

        public String getServerJar() {
            return serverJar;
        }

        public void setServerJar(String serverJar) {
            this.serverJar = serverJar;
        }

        public String getServerDirectory() {
            return serverDirectory;
        }

        public void setServerDirectory(String serverDirectory) {
            this.serverDirectory = serverDirectory;
        }
    }
}
