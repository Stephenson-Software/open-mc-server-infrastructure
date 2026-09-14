package com.openmc.webapp.controller;

import com.openmc.webapp.config.ServerConfig;
import com.openmc.webapp.dto.DashboardStatus;
import com.openmc.webapp.dto.PluginDeleteRequest;
import com.openmc.webapp.dto.PluginListRequest;
import com.openmc.webapp.dto.PluginListResponse;
import com.openmc.webapp.dto.PluginOperationResponse;
import com.openmc.webapp.dto.WorldDeleteRequest;
import com.openmc.webapp.dto.WorldListRequest;
import com.openmc.webapp.dto.WorldListResponse;
import com.openmc.webapp.dto.WorldOperationResponse;
import com.openmc.webapp.model.ActivityTrackerStats;
import com.openmc.webapp.model.DeploymentRecord;
import com.openmc.webapp.model.LeaderboardEntry;
import com.openmc.webapp.model.ServerState;
import com.openmc.webapp.service.ActivityTrackerService;
import com.openmc.webapp.service.AlertNotificationService;
import com.openmc.webapp.service.DeploymentHistoryService;
import com.openmc.webapp.service.PluginService;
import com.openmc.webapp.service.RconService;
import com.openmc.webapp.service.ServerStateService;
import com.openmc.webapp.service.WorldService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Controller
@Validated
public class ServerController {
    
    private static final Logger logger = LoggerFactory.getLogger(ServerController.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Set<String> VALID_DEPLOYMENT_STATUSES = Set.of("SUCCESS", "FAILURE");
    
    private final RconService rconService;
    private final ServerConfig serverConfig;
    private final ActivityTrackerService activityTrackerService;
    private final PluginService pluginService;
    private final WorldService worldService;
    private final AlertNotificationService alertNotificationService;
    private final com.openmc.webapp.service.MinecraftWrapperService minecraftWrapperService;
    private final DeploymentHistoryService deploymentHistoryService;
    private final ServerStateService serverStateService;

    @org.springframework.beans.factory.annotation.Value("${deployment.auth.token:}")
    private String deploymentAuthToken;

    @org.springframework.beans.factory.annotation.Value("${deploy.auth.token:}")
    private String deployAuthToken;

    private volatile boolean deploymentTokenWarningLogged = false;
    
    public ServerController(RconService rconService, ServerConfig serverConfig,
                          ActivityTrackerService activityTrackerService,
                          PluginService pluginService,
                          WorldService worldService,
                          AlertNotificationService alertNotificationService,
                          com.openmc.webapp.service.MinecraftWrapperService minecraftWrapperService,
                          DeploymentHistoryService deploymentHistoryService,
                          ServerStateService serverStateService) {
        this.rconService = rconService;
        this.serverConfig = serverConfig;
        this.activityTrackerService = activityTrackerService;
        this.pluginService = pluginService;
        this.worldService = worldService;
        this.alertNotificationService = alertNotificationService;
        this.minecraftWrapperService = minecraftWrapperService;
        this.deploymentHistoryService = deploymentHistoryService;
        this.serverStateService = serverStateService;
    }
    
    /**
     * Adds common attributes to all views to avoid duplication across handler methods.
     * This ensures consistency and reduces the risk of missing attributes on specific pages.
     */
    @ModelAttribute
    public void addCommonAttributes(Model model) {
        model.addAttribute("dashboardTitle", serverConfig.getDashboardTitle());
        model.addAttribute("dashboardSubtitle", serverConfig.getDashboardSubtitle());
        model.addAttribute("dashboardPrimaryColor", serverConfig.getDashboardPrimaryColor());
        model.addAttribute("dashboardSecondaryColor", serverConfig.getDashboardSecondaryColor());
        model.addAttribute("dashboardDarkMode", serverConfig.isDashboardDarkMode());
        model.addAttribute("dynmapUrl", serverConfig.getDynmapUrl());
        model.addAttribute("bluemapUrl", serverConfig.getBluemapUrl());
        model.addAttribute("accordionChatUrl", serverConfig.getAccordionChatUrl());
    }
    
    @GetMapping("/")
    public String index() {
        return "redirect:/public";
    }
    
    @GetMapping("/public")
    public String publicPage(Model model) {
        RconService.ServerStatus status = currentStatus();
        model.addAttribute("status", status);
        model.addAttribute("serverState", resolveState(status));
        model.addAttribute("refreshIntervalMs", serverConfig.getRefreshIntervalMs());
        model.addAttribute("lastFetchTime", rconService.getLastFetchTime());
        model.addAttribute("activityTrackerEnabled", activityTrackerService.isEnabled());
        return "public";
    }
    
    @GetMapping("/admin")
    public String adminPage(Model model) {
        return "admin";
    }
    
    @PostMapping("/api/command")
    @ResponseBody
    public Map<String, String> sendCommand(@RequestBody Map<String, String> payload) {
        String username = payload.get("username");
        String password = payload.get("password");
        String command = payload.get("command");
        
        // Validate credentials
        if (username == null || password == null) {
            alertNotificationService.sendWarningAlert(
                "Admin Authentication Failed",
                "Failed authentication attempt - missing credentials"
            );
            return Map.of("result", "Error: Username and password are required");
        }
        
        if (!validateCredentials(username, password)) {
            alertNotificationService.sendWarningAlert(
                "Admin Authentication Failed",
                "Failed authentication attempt for admin command endpoint"
            );
            return Map.of("result", "Error: Invalid username or password");
        }
        
        // Validate command
        if (command == null || command.trim().isEmpty()) {
            return Map.of("result", "Error: Command cannot be empty");
        }
        
        String result = rconService.sendCommand(command);
        
        // Send alert for successful command execution
        alertNotificationService.sendInfoAlert(
            "Server Command Executed",
            String.format("User '%s' executed command: %s", username, command)
        );
        
        return Map.of("result", result);
    }
    
    @GetMapping("/api/status")
    @ResponseBody
    public DashboardStatus getStatus() {
        RconService.ServerStatus status = currentStatus();
        return new DashboardStatus(status, resolveState(status));
    }

    /**
     * The RCON status, unless the wrapper is known to be scaled to zero — then an
     * offline status is returned without connecting, because a Service with no
     * endpoints drops connections instead of refusing them and the page would hang.
     */
    private RconService.ServerStatus currentStatus() {
        if (serverStateService.knownAsleep().orElse(false)) {
            return rconService.offlineStatus();
        }
        return rconService.getServerStatus();
    }

    /**
     * The state shown on the dashboard: the RCON online flag, refined by the wrapper
     * StatefulSet's replica count when the dashboard is sleep-aware.
     */
    private ServerState resolveState(RconService.ServerStatus status) {
        boolean online = status != null && status.isOnline();
        return serverStateService.resolve(online);
    }
    
    @GetMapping("/api/resources")
    @ResponseBody
    public RconService.ResourceUsage getResources() {
        return rconService.getResourceUsage();
    }
    
    @GetMapping("/api/history")
    @ResponseBody
    public Map<String, Object> getHistory() {
        return Map.of("history", rconService.getRetrievalHistory());
    }
    
    @GetMapping("/api/history/max-size")
    @ResponseBody
    public Map<String, Integer> getHistoryMaxSize() {
        return Map.of("maxSize", rconService.getMaxHistorySize());
    }
    
    @PostMapping("/api/history/max-size")
    @ResponseBody
    public Map<String, Object> setHistoryMaxSize(@RequestBody Map<String, Integer> payload) {
        Integer maxSize = payload.get("maxSize");
        
        if (maxSize == null) {
            return Map.of("success", false, "error", "maxSize is required");
        }
        
        if (maxSize <= 0) {
            return Map.of("success", false, "error", "maxSize must be greater than 0");
        }
        
        try {
            rconService.setMaxHistorySize(maxSize);
            return Map.of("success", true, "maxSize", maxSize);
        } catch (IllegalArgumentException e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }
    
    @GetMapping("/api/activity-tracker/stats")
    @ResponseBody
    public ActivityTrackerStats getActivityTrackerStats() {
        logger.debug("API request: /api/activity-tracker/stats");
        ActivityTrackerStats stats = activityTrackerService.getStats();
        if (stats == null) {
            logger.warn("Activity Tracker stats request returned null - check if integration is enabled and API is accessible");
        }
        return stats;
    }
    
    @GetMapping("/api/activity-tracker/leaderboard")
    @ResponseBody
    public List<LeaderboardEntry> getActivityTrackerLeaderboard() {
        logger.debug("API request: /api/activity-tracker/leaderboard");
        List<LeaderboardEntry> leaderboard = activityTrackerService.getLeaderboard();
        if (leaderboard.isEmpty()) {
            logger.warn("Activity Tracker leaderboard request returned empty - check if integration is enabled and API is accessible");
        }
        return leaderboard;
    }
    
    @GetMapping("/api/activity-tracker/enabled")
    @ResponseBody
    public Map<String, Boolean> getActivityTrackerEnabled() {
        boolean enabled = activityTrackerService.isEnabled();
        logger.debug("API request: /api/activity-tracker/enabled - returning: {}", enabled);
        return Map.of("enabled", enabled);
    }
    
    /**
     * Validates admin credentials using constant-time comparison to prevent timing attacks
     */
    private boolean validateCredentials(String username, String password) {
        String adminUsername = serverConfig.getAdminUsername();
        String adminPassword = serverConfig.getAdminPassword();
        
        // Check for null values
        if (username == null || password == null || adminUsername == null || adminPassword == null) {
            return false;
        }
        
        // Use constant-time comparison to prevent timing attacks
        return MessageDigest.isEqual(username.getBytes(), adminUsername.getBytes()) &&
               MessageDigest.isEqual(password.getBytes(), adminPassword.getBytes());
    }
    
    @PostMapping(value = "/api/plugins/list", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public PluginListResponse listPlugins(@RequestBody PluginListRequest request) {
        // Validate credentials
        if (!validateCredentials(request.getUsername(), request.getPassword())) {
            alertNotificationService.sendWarningAlert(
                "Plugin List Authentication Failed",
                "Failed authentication attempt for plugin list endpoint"
            );
            return PluginListResponse.error("Invalid username or password");
        }
        
        List<String> plugins = pluginService.listPlugins();
        return PluginListResponse.success(plugins);
    }
    
    @PostMapping(value = "/api/plugins/upload", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public PluginOperationResponse uploadPlugin(@RequestParam(required = false) String username, 
                                                @RequestParam(required = false) String password,
                                                @RequestParam(value = "file", required = false) MultipartFile file) {
        // Validate required parameters
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            return PluginOperationResponse.error("Missing username or password");
        }
        
        if (file == null || file.isEmpty()) {
            return PluginOperationResponse.error("No file provided");
        }
        
        // Validate credentials
        if (!validateCredentials(username, password)) {
            alertNotificationService.sendWarningAlert(
                "Plugin Upload Authentication Failed",
                "Failed authentication attempt for plugin upload endpoint"
            );
            return PluginOperationResponse.error("Invalid username or password");
        }
        
        String result = pluginService.uploadPlugin(file);
        boolean success = !result.startsWith("Error");
        
        if (success) {
            alertNotificationService.sendInfoAlert(
                "Plugin Uploaded Successfully",
                String.format("User '%s' uploaded plugin: %s", username, file.getOriginalFilename())
            );
            return PluginOperationResponse.success(result);
        } else {
            return PluginOperationResponse.error(result);
        }
    }
    
    @PostMapping(value = "/api/plugins/delete", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public PluginOperationResponse deletePlugin(@RequestBody PluginDeleteRequest request) {
        // Validate required parameters are present
        if (request.getUsername() == null || request.getUsername().isEmpty() || 
            request.getPassword() == null || request.getPassword().isEmpty() ||
            request.getFilename() == null || request.getFilename().isEmpty()) {
            return PluginOperationResponse.error("Missing required parameters");
        }
        
        // Validate credentials
        if (!validateCredentials(request.getUsername(), request.getPassword())) {
            alertNotificationService.sendWarningAlert(
                "Plugin Delete Authentication Failed",
                "Failed authentication attempt for plugin deletion endpoint"
            );
            return PluginOperationResponse.error("Invalid username or password");
        }
        
        String result = pluginService.deletePlugin(request.getFilename());
        boolean success = !result.startsWith("Error");
        
        if (success) {
            alertNotificationService.sendInfoAlert(
                "Plugin Deleted Successfully",
                String.format("User '%s' deleted plugin: %s", request.getUsername(), request.getFilename())
            );
            return PluginOperationResponse.success(result);
        } else {
            return PluginOperationResponse.error(result);
        }
    }
    
    @PostMapping(value = "/api/world/upload", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public WorldOperationResponse uploadWorld(@RequestParam(required = false) String username,
                                               @RequestParam(required = false) String password,
                                               @RequestParam(value = "file", required = false) MultipartFile file) {
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            return WorldOperationResponse.error("Missing username or password");
        }

        if (file == null || file.isEmpty()) {
            return WorldOperationResponse.error("No file provided");
        }

        if (!validateCredentials(username, password)) {
            alertNotificationService.sendWarningAlert(
                "World Upload Authentication Failed",
                "Failed authentication attempt for world upload endpoint"
            );
            return WorldOperationResponse.error("Invalid username or password");
        }

        if (deployAuthToken == null || deployAuthToken.trim().isEmpty()) {
            logger.error("deploy.auth.token is not configured; world upload is unavailable");
            return WorldOperationResponse.error("World upload is not configured on this server");
        }

        var result = minecraftWrapperService.uploadWorld(file, deployAuthToken);

        if (result.success()) {
            alertNotificationService.sendInfoAlert(
                "World Uploaded Successfully",
                String.format("User '%s' uploaded a new world map", username)
            );
            return WorldOperationResponse.success(result.message());
        } else {
            return WorldOperationResponse.error(result.message());
        }
    }

    @PostMapping(value = "/api/world/list", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public WorldListResponse listWorlds(@RequestBody WorldListRequest request) {
        if (!validateCredentials(request.getUsername(), request.getPassword())) {
            alertNotificationService.sendWarningAlert(
                "World List Authentication Failed",
                "Failed authentication attempt for world list endpoint"
            );
            return WorldListResponse.error("Invalid username or password");
        }
        return WorldListResponse.success(worldService.listWorlds());
    }

    @PostMapping(value = "/api/world/delete", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public WorldOperationResponse deleteWorld(@RequestBody WorldDeleteRequest request) {
        if (request.getUsername() == null || request.getUsername().isEmpty()
                || request.getPassword() == null || request.getPassword().isEmpty()
                || request.getName() == null || request.getName().isEmpty()) {
            return WorldOperationResponse.error("Missing required parameters");
        }
        if (!validateCredentials(request.getUsername(), request.getPassword())) {
            alertNotificationService.sendWarningAlert(
                "World Delete Authentication Failed",
                "Failed authentication attempt for world delete endpoint"
            );
            return WorldOperationResponse.error("Invalid username or password");
        }

        String result = worldService.deleteWorld(request.getName());
        boolean success = !result.startsWith("Error");

        if (success) {
            alertNotificationService.sendInfoAlert(
                "World Deleted Successfully",
                String.format("User '%s' deleted world: %s", request.getUsername(), request.getName())
            );
            return WorldOperationResponse.success(result);
        } else {
            return WorldOperationResponse.error(result);
        }
    }

    @PostMapping("/api/server/start")
    @ResponseBody
    public Map<String, Object> startServer(@RequestBody Map<String, String> payload) {
        String username = payload.get("username");
        String password = payload.get("password");
        
        // Validate credentials
        if (username == null || password == null || !validateCredentials(username, password)) {
            alertNotificationService.sendWarningAlert(
                "Server Start Authentication Failed",
                "Failed authentication attempt for server start endpoint"
            );
            return Map.of("success", false, "message", "Invalid username or password");
        }
        
        // A sleeping wrapper (StatefulSet at 0 replicas) is woken by scaling it up; the
        // wrapper's own auto-start then brings the game up. Anything else goes to the
        // wrapper's start endpoint as before.
        var result = serverStateService.wakeIfAsleep().orElseGet(minecraftWrapperService::startServer);

        if (result.success()) {
            alertNotificationService.sendInfoAlert(
                "Server Start Initiated",
                String.format("User '%s' initiated server start", username)
            );
        }
        return Map.of("success", result.success(), "message", result.message());
    }
    
    @PostMapping("/api/server/stop")
    @ResponseBody
    public Map<String, Object> stopServer(@RequestBody Map<String, String> payload) {
        String username = payload.get("username");
        String password = payload.get("password");
        
        // Validate credentials
        if (username == null || password == null || !validateCredentials(username, password)) {
            alertNotificationService.sendWarningAlert(
                "Server Stop Authentication Failed",
                "Failed authentication attempt for server stop endpoint"
            );
            return Map.of("success", false, "message", "Invalid username or password");
        }
        
        var result = minecraftWrapperService.stopServer();

        if (result.success()) {
            alertNotificationService.sendInfoAlert(
                "Server Stop Initiated",
                String.format("User '%s' initiated server stop", username)
            );
        }
        return Map.of("success", result.success(), "message", result.message());
    }
    
    @PostMapping("/api/server/restart")
    @ResponseBody
    public Map<String, Object> restartServer(@RequestBody Map<String, String> payload) {
        String username = payload.get("username");
        String password = payload.get("password");
        
        // Validate credentials
        if (username == null || password == null || !validateCredentials(username, password)) {
            alertNotificationService.sendWarningAlert(
                "Server Restart Authentication Failed",
                "Failed authentication attempt for server restart endpoint"
            );
            return Map.of("success", false, "message", "Invalid username or password");
        }
        
        var result = minecraftWrapperService.restartServer();

        if (result.success()) {
            alertNotificationService.sendInfoAlert(
                "Server Restart Initiated",
                String.format("User '%s' initiated server restart", username)
            );
        }
        return Map.of("success", result.success(), "message", result.message());
    }
    
    @GetMapping("/api/deployment-history")
    @ResponseBody
    public Map<String, Object> getDeploymentHistory() {
        List<DeploymentRecord> history = deploymentHistoryService.getDeploymentHistory();
        return Map.of("deployments", history);
    }

    @PostMapping("/api/deployment-history")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> recordDeployment(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, String> payload) {
        if (!isDeploymentAuthorized(authHeader)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "Unauthorized"));
        }

        String pluginName = payload.get("pluginName");
        String status = payload.get("status");
        String source = payload.get("source");
        String branch = payload.get("branch");
        String repoUrl = payload.get("repoUrl");
        String message = payload.get("message");

        if (pluginName == null || pluginName.isEmpty() || status == null || status.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "pluginName and status are required"));
        }

        String normalizedStatus = status.toUpperCase();
        if (!VALID_DEPLOYMENT_STATUSES.contains(normalizedStatus)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message",
                            "Invalid status. Allowed values: " + VALID_DEPLOYMENT_STATUSES));
        }

        deploymentHistoryService.recordDeployment(pluginName, normalizedStatus, source, branch, repoUrl, message);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * Returns {@code true} if the provided {@code Authorization} header carries a Bearer
     * token that matches the configured deployment auth token.
     * Uses constant-time comparison to prevent timing attacks.
     */
    private boolean isDeploymentAuthorized(String authHeader) {
        if (deploymentAuthToken == null || deploymentAuthToken.trim().isEmpty()) {
            if (!deploymentTokenWarningLogged) {
                logger.warn("deployment.auth.token is not configured; all deployment record requests will be rejected");
                deploymentTokenWarningLogged = true;
            }
            return false;
        }
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return false;
        }
        String providedToken = authHeader.substring(BEARER_PREFIX.length());
        return MessageDigest.isEqual(
                deploymentAuthToken.getBytes(StandardCharsets.UTF_8),
                providedToken.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/player/{playerName}")
    public String playerProfile(@PathVariable String playerName, Model model) {
        com.openmc.webapp.model.PlayerProfile profile = activityTrackerService.getPlayerProfile(playerName);
        
        if (profile == null) {
            model.addAttribute("error", "Player not found: " + playerName);
            model.addAttribute("playerName", playerName);
        } else {
            model.addAttribute("profile", profile);
        }
        
        return "player";
    }
    
    @GetMapping("/api/player/{playerName}")
    @ResponseBody
    public ResponseEntity<com.openmc.webapp.model.PlayerProfile> getPlayerProfile(@PathVariable String playerName) {
        com.openmc.webapp.model.PlayerProfile profile = activityTrackerService.getPlayerProfile(playerName);
        if (profile == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(profile);
    }
}
