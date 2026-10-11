package com.openmc.minecraftwrapper.controller;

import com.openmc.minecraftwrapper.service.AlertService;
import com.openmc.minecraftwrapper.service.PluginDeployService;
import com.openmc.minecraftwrapper.service.WebAppNotificationService;
import com.openmc.minecraftwrapper.util.BearerTokenAuthenticator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Slf4j
@Validated
@RestController
@RequestMapping("/api/plugins")
public class PluginDeployController {

    @Value("${deploy.auth.token:}")
    private String deployAuthToken;

    private final PluginDeployService pluginDeployService;
    private final AlertService alertService;
    private final WebAppNotificationService webAppNotificationService;

    public PluginDeployController(PluginDeployService pluginDeployService, AlertService alertService,
                                   WebAppNotificationService webAppNotificationService) {
        this.pluginDeployService = pluginDeployService;
        this.alertService = alertService;
        this.webAppNotificationService = webAppNotificationService;
    }

    /**
     * Deploy a plugin JAR with the uploaded file.
     *
     * <p>Authentication is required via an {@code Authorization: Bearer <token>} header.
     * The token must match the value configured in {@code deploy.auth.token}.
     *
     * @param authHeader the HTTP {@code Authorization} header
     * @param pluginName the filename of the plugin JAR to deploy (e.g. {@code MyPlugin.jar})
     * @param branch     optional Git branch name that triggered the deployment
     * @param repoUrl    optional URL of the repository that initiated the deployment
     * @param file       the new JAR file
     * @return 200 OK on success, 400 on bad input, 401 on auth failure, 500 on I/O error
     */
    @PostMapping("/deploy")
    public ResponseEntity<String> deploy(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam("pluginName") String pluginName,
            @RequestParam(value = "branch", required = false) String branch,
            @RequestParam(value = "repoUrl", required = false) String repoUrl,
            @RequestParam("file") MultipartFile file) {

        if (!isAuthorized(authHeader)) {
            log.warn("Unauthorized plugin deploy attempt");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }

        log.info("Received plugin deploy request");

        try {
            pluginDeployService.replacePlugin(pluginName, file);
            alertService.sendPluginDeploySuccessAlert(pluginName, branch, repoUrl);
            webAppNotificationService.notifyDeploymentSuccess(pluginName, branch, repoUrl);
            return ResponseEntity.ok("Plugin deployed successfully");
        } catch (IllegalArgumentException e) {
            log.warn("Invalid plugin deploy request: {}", e.getMessage());
            alertService.sendPluginDeployFailureAlert(pluginName, e.getMessage(), branch, repoUrl);
            webAppNotificationService.notifyDeploymentFailure(pluginName, e.getMessage(), branch, repoUrl);
            return ResponseEntity.badRequest().body("Invalid request");
        } catch (IOException e) {
            log.error("Failed to deploy plugin: {}", e.getMessage());
            alertService.sendPluginDeployFailureAlert(pluginName, e.getMessage(), branch, repoUrl);
            webAppNotificationService.notifyDeploymentFailure(pluginName, e.getMessage(), branch, repoUrl);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to deploy plugin");
        }
    }

    /**
     * The outcome of the plugin selection reconciled at the last container start
     * ({@code plugins/.omcsi-applied.json}): the selection's SHA-256 and a per-plugin
     * outcome. Read-only and unauthenticated, like {@code /api/server/status}; the
     * wrapper API is reachable only inside the cluster.
     *
     * @return 200 with the report, 404 when no selection has been reconciled, 500 on I/O error
     */
    @GetMapping(value = "/applied", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> applied() {
        try {
            return pluginDeployService.readAppliedReport()
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IOException e) {
            log.error("Failed to read the plugin selection report: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private boolean isAuthorized(String authHeader) {
        return BearerTokenAuthenticator.isAuthorized(authHeader, deployAuthToken, "plugin deploy");
    }
}
