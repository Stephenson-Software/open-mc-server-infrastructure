package com.openmc.webapp.controller;

import com.openmc.webapp.config.ServerConfig;
import com.openmc.webapp.service.ActivityTrackerService;
import com.openmc.webapp.service.AlertNotificationService;
import com.openmc.webapp.service.MinecraftWrapperService.WrapperResult;
import com.openmc.webapp.service.PluginService;
import com.openmc.webapp.model.ServerState;
import com.openmc.webapp.service.RconService;
import com.openmc.webapp.service.ServerStateService;
import com.openmc.webapp.service.WorldService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

@WebMvcTest(ServerController.class)
// The world upload endpoint short-circuits when deploy.auth.token is unset, so give it a value.
@TestPropertySource(properties = "deploy.auth.token=test-deploy-token")
@DisplayName("ServerController Tests")
class ServerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RconService rconService;

    @MockBean
    private ServerConfig serverConfig;
    
    @MockBean
    private ActivityTrackerService activityTrackerService;
    
    @MockBean
    private PluginService pluginService;

    @MockBean
    private WorldService worldService;
    
    @MockBean
    private AlertNotificationService alertNotificationService;
    
    @MockBean
    private com.openmc.webapp.service.MinecraftWrapperService minecraftWrapperService;
    
    @MockBean
    private com.openmc.webapp.service.DeploymentHistoryService deploymentHistoryService;

    @MockBean
    private ServerStateService serverStateService;

    private RconService.ServerStatus mockStatus;

    @BeforeEach
    void setUp() {
        RconService.ResourceUsage mockResourceUsage = new RconService.ResourceUsage("20.0, 20.0, 20.0", "1024MB", "2048MB", "1024MB", 50.0);
        mockStatus = new RconService.ServerStatus(serverConfig, "There are 0 of a max of 20 players online", mockResourceUsage);
        
        when(serverConfig.getMotd()).thenReturn("Test Server");
        when(serverConfig.getMaxPlayers()).thenReturn(20);
        when(serverConfig.getDynmapUrl()).thenReturn("");
        when(serverConfig.getBluemapUrl()).thenReturn("");
        when(serverConfig.getAdminUsername()).thenReturn("admin");
        when(serverConfig.getAdminPassword()).thenReturn("admin");
        when(activityTrackerService.isEnabled()).thenReturn(false);
        // Not sleep-aware unless a test says otherwise: the RCON flag passes straight through.
        when(serverStateService.resolve(anyBoolean()))
                .thenAnswer(invocation -> (boolean) invocation.getArgument(0) ? ServerState.ONLINE : ServerState.OFFLINE);
        when(serverStateService.wakeIfAsleep()).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("Should redirect to /public on GET /")
    void shouldRedirectToPublicOnGetRoot() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/public"));
    }

    @Test
    @DisplayName("Should return public page on GET /public")
    void shouldReturnPublicPageOnGetPublic() throws Exception {
        when(rconService.getServerStatus()).thenReturn(mockStatus);

        mockMvc.perform(get("/public"))
                .andExpect(status().isOk())
                .andExpect(view().name("public"))
                .andExpect(model().attributeExists("status"))
                .andExpect(model().attributeExists("dynmapUrl"))
                .andExpect(model().attributeExists("bluemapUrl"));
    }

    @Test
    @DisplayName("Should return admin page on GET /admin")
    void shouldReturnAdminPageOnGetAdmin() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin"));
    }

    @Test
    @DisplayName("Should return server status on GET /api/status")
    void shouldReturnServerStatusOnGetApiStatus() throws Exception {
        when(rconService.getServerStatus()).thenReturn(mockStatus);

        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                // the RCON fields stay at the top level for existing consumers
                .andExpect(jsonPath("$.online").value(true))
                .andExpect(jsonPath("$.resourceUsage.tps").value("20.0, 20.0, 20.0"))
                .andExpect(jsonPath("$.serverState").value("ONLINE"));
    }

    /** What RconService produces when nothing answers on the RCON port. */
    private RconService.ServerStatus unreachableStatus() {
        RconService.ResourceUsage usage = new RconService.ResourceUsage("N/A", "N/A", "N/A", "N/A", 0.0);
        return new RconService.ServerStatus(serverConfig, "Error: Unable to connect to server - Connection refused", usage);
    }

    @Test
    @DisplayName("Should report ASLEEP on GET /api/status when the wrapper is scaled to zero")
    void shouldReportAsleepOnGetApiStatus() throws Exception {
        RconService.ServerStatus unreachable = unreachableStatus();
        when(rconService.getServerStatus()).thenReturn(unreachable);
        when(serverStateService.resolve(false)).thenReturn(ServerState.ASLEEP);

        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.online").value(false))
                .andExpect(jsonPath("$.serverState").value("ASLEEP"))
                .andExpect(jsonPath("$.serverStateMessage").value(containsString("join to wake")));
    }

    @Test
    @DisplayName("Should render the public page as asleep rather than as an error")
    void shouldRenderPublicPageAsleep() throws Exception {
        RconService.ServerStatus unreachable = unreachableStatus();
        when(rconService.getServerStatus()).thenReturn(unreachable);
        when(serverStateService.resolve(false)).thenReturn(ServerState.ASLEEP);

        mockMvc.perform(get("/public"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("serverState", ServerState.ASLEEP))
                .andExpect(content().string(containsString("status-asleep")))
                .andExpect(content().string(containsString("join to wake")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Unable to connect to server"))));
    }

    @Test
    @DisplayName("Should render the public page as waking while the wrapper comes up")
    void shouldRenderPublicPageWaking() throws Exception {
        RconService.ServerStatus unreachable = unreachableStatus();
        when(rconService.getServerStatus()).thenReturn(unreachable);
        when(serverStateService.resolve(false)).thenReturn(ServerState.WAKING);

        mockMvc.perform(get("/public"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("status-waking")))
                .andExpect(content().string(containsString("The server is starting")));
    }

    @Test
    @DisplayName("Should render the public page offline when the wrapper is genuinely down")
    void shouldRenderPublicPageOffline() throws Exception {
        RconService.ServerStatus unreachable = unreachableStatus();
        when(rconService.getServerStatus()).thenReturn(unreachable);

        mockMvc.perform(get("/public"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("status-offline")))
                .andExpect(content().string(containsString("Unable to connect to server")));
    }

    @Test
    @DisplayName("Should return resource usage on GET /api/resources")
    void shouldReturnResourceUsageOnGetApiResources() throws Exception {
        RconService.ResourceUsage mockResourceUsage = new RconService.ResourceUsage("20.0, 20.0, 20.0", "1024MB", "2048MB", "1024MB", 50.0);
        when(rconService.getResourceUsage()).thenReturn(mockResourceUsage);

        mockMvc.perform(get("/api/resources"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.tps").value("20.0, 20.0, 20.0"))
                .andExpect(jsonPath("$.memoryUsed").value("1024MB"));
    }

    @Test
    @DisplayName("Should accept valid command with authentication")
    void shouldAcceptValidCommandWithAuthentication() throws Exception {
        when(rconService.sendCommand("list")).thenReturn("There are 0 of a max of 20 players online");

        mockMvc.perform(post("/api/command")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin\",\"command\":\"list\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").exists());
        
        // Verify alert was sent
        verify(alertNotificationService, times(1)).sendInfoAlert(
            anyString(), 
            anyString()
        );
    }

    @Test
    @DisplayName("Should reject command without username")
    void shouldRejectCommandWithoutUsername() throws Exception {
        mockMvc.perform(post("/api/command")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"admin\",\"command\":\"list\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(containsString("Username and password are required")));
    }

    @Test
    @DisplayName("Should reject command without password")
    void shouldRejectCommandWithoutPassword() throws Exception {
        mockMvc.perform(post("/api/command")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"command\":\"list\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(containsString("Username and password are required")));
    }

    @Test
    @DisplayName("Should reject command with invalid credentials")
    void shouldRejectCommandWithInvalidCredentials() throws Exception {
        mockMvc.perform(post("/api/command")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wrong\",\"password\":\"wrong\",\"command\":\"list\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(containsString("Invalid username or password")));
        
        // Verify warning alert was sent for failed authentication
        verify(alertNotificationService, times(1)).sendWarningAlert(
            anyString(), 
            anyString()
        );
    }

    @Test
    @DisplayName("Should reject empty command")
    void shouldRejectEmptyCommand() throws Exception {
        mockMvc.perform(post("/api/command")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin\",\"command\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(containsString("Command cannot be empty")));
    }

    @Test
    @DisplayName("Should reject null command")
    void shouldRejectNullCommand() throws Exception {
        mockMvc.perform(post("/api/command")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(containsString("Command cannot be empty")));
    }
    
    @Test
    @DisplayName("Should list plugins with valid credentials")
    void shouldListPluginsWithValidCredentials() throws Exception {
        List<String> plugins = Arrays.asList("plugin1.jar", "plugin2.jar");
        when(pluginService.listPlugins()).thenReturn(plugins);
        
        mockMvc.perform(post("/api/plugins/list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.plugins[0]").value("plugin1.jar"))
                .andExpect(jsonPath("$.plugins[1]").value("plugin2.jar"));
    }
    
    @Test
    @DisplayName("Should reject list plugins with invalid credentials")
    void shouldRejectListPluginsWithInvalidCredentials() throws Exception {
        mockMvc.perform(post("/api/plugins/list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wrong\",\"password\":\"wrong\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value(containsString("Invalid username or password")));
    }
    
    @Test
    @DisplayName("Should upload plugin with valid credentials")
    void shouldUploadPluginWithValidCredentials() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "test-plugin.jar",
            "application/java-archive",
            "test content".getBytes()
        );
        
        when(pluginService.uploadPlugin(any())).thenReturn("Plugin uploaded successfully: test-plugin.jar");
        
        mockMvc.perform(multipart("/api/plugins/upload")
                        .file(file)
                        .param("username", "admin")
                        .param("password", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value(containsString("uploaded successfully")));
        
        // Verify alert was sent for successful upload
        verify(alertNotificationService, times(1)).sendInfoAlert(
            anyString(), 
            anyString()
        );
    }
    
    @Test
    @DisplayName("Should reject upload plugin with invalid credentials")
    void shouldRejectUploadPluginWithInvalidCredentials() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "test-plugin.jar",
            "application/java-archive",
            "test content".getBytes()
        );
        
        mockMvc.perform(multipart("/api/plugins/upload")
                        .file(file)
                        .param("username", "wrong")
                        .param("password", "wrong"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value(containsString("Invalid username or password")));
        
        // Verify warning alert was sent for failed authentication
        verify(alertNotificationService, times(1)).sendWarningAlert(
            anyString(), 
            anyString()
        );
    }
    
    @Test
    @DisplayName("Should delete plugin with valid credentials")
    void shouldDeletePluginWithValidCredentials() throws Exception {
        when(pluginService.deletePlugin(anyString())).thenReturn("Plugin deleted successfully: test-plugin.jar");
        
        mockMvc.perform(post("/api/plugins/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin\",\"filename\":\"test-plugin.jar\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value(containsString("deleted successfully")));
        
        // Verify alert was sent for successful deletion
        verify(alertNotificationService, times(1)).sendInfoAlert(
            anyString(), 
            anyString()
        );
    }
    
    @Test
    @DisplayName("Should reject delete plugin with invalid credentials")
    void shouldRejectDeletePluginWithInvalidCredentials() throws Exception {
        mockMvc.perform(post("/api/plugins/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wrong\",\"password\":\"wrong\",\"filename\":\"test-plugin.jar\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value(containsString("Invalid username or password")));
        
        // Verify warning alert was sent for failed authentication
        verify(alertNotificationService, times(1)).sendWarningAlert(
            anyString(), 
            anyString()
        );
    }
    
    @Test
    @DisplayName("Should return player profile page when player exists")
    void shouldReturnPlayerProfilePageWhenPlayerExists() throws Exception {
        com.openmc.webapp.model.PlayerProfile mockProfile = new com.openmc.webapp.model.PlayerProfile(
            "550e8400-e29b-41d4-a716-446655440000",
            "TestPlayer",
            123.5,
            50,
            1
        );
        
        when(activityTrackerService.getPlayerProfile("TestPlayer")).thenReturn(mockProfile);
        
        mockMvc.perform(get("/player/TestPlayer"))
                .andExpect(status().isOk())
                .andExpect(view().name("player"))
                .andExpect(model().attributeExists("profile"))
                .andExpect(model().attribute("profile", mockProfile));
    }
    
    @Test
    @DisplayName("Should return error page when player not found")
    void shouldReturnErrorPageWhenPlayerNotFound() throws Exception {
        when(activityTrackerService.getPlayerProfile("UnknownPlayer")).thenReturn(null);
        
        mockMvc.perform(get("/player/UnknownPlayer"))
                .andExpect(status().isOk())
                .andExpect(view().name("player"))
                .andExpect(model().attributeExists("error"))
                .andExpect(model().attributeExists("playerName"));
    }
    
    @Test
    @DisplayName("Should return player profile via API when player exists")
    void shouldReturnPlayerProfileViaApiWhenPlayerExists() throws Exception {
        com.openmc.webapp.model.PlayerProfile mockProfile = new com.openmc.webapp.model.PlayerProfile(
            "550e8400-e29b-41d4-a716-446655440000",
            "TestPlayer",
            123.5,
            50,
            1
        );
        
        when(activityTrackerService.getPlayerProfile("TestPlayer")).thenReturn(mockProfile);
        
        mockMvc.perform(get("/api/player/TestPlayer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerName").value("TestPlayer"))
                .andExpect(jsonPath("$.playerUuid").value("550e8400-e29b-41d4-a716-446655440000"))
                .andExpect(jsonPath("$.hoursPlayed").value(123.5))
                .andExpect(jsonPath("$.totalLogins").value(50))
                .andExpect(jsonPath("$.leaderboardRank").value(1));
    }
    
    @Test
    @DisplayName("Should return 404 via API when player not found")
    void shouldReturn404ViaApiWhenPlayerNotFound() throws Exception {
        when(activityTrackerService.getPlayerProfile("UnknownPlayer")).thenReturn(null);

        mockMvc.perform(get("/api/player/UnknownPlayer"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Should report the wrapper's success message when starting the server")
    void shouldReportSuccessMessageOnServerStart() throws Exception {
        when(minecraftWrapperService.startServer())
                .thenReturn(WrapperResult.success("Server start initiated"));

        mockMvc.perform(post("/api/server/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Server start initiated"));

        verify(alertNotificationService, times(1)).sendInfoAlert(anyString(), anyString());
    }

    @Test
    @DisplayName("Should surface the wrapper's rejection reason when starting the server")
    void shouldSurfaceWrapperReasonOnServerStart() throws Exception {
        when(minecraftWrapperService.startServer())
                .thenReturn(WrapperResult.failure("Server is already running"));

        mockMvc.perform(post("/api/server/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Server is already running"));

        // A rejected start is not an event worth alerting on
        verify(alertNotificationService, never()).sendInfoAlert(anyString(), anyString());
    }

    @Test
    @DisplayName("Should wake a sleeping wrapper on start instead of calling its start endpoint")
    void shouldWakeSleepingWrapperOnStart() throws Exception {
        when(serverStateService.wakeIfAsleep())
                .thenReturn(Optional.of(WrapperResult.success("Server is waking up")));

        mockMvc.perform(post("/api/server/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Server is waking up"));

        verify(minecraftWrapperService, never()).startServer();
        verify(alertNotificationService, times(1)).sendInfoAlert(anyString(), anyString());
    }

    @Test
    @DisplayName("Should surface a failed wake without falling through to the wrapper")
    void shouldSurfaceFailedWake() throws Exception {
        when(serverStateService.wakeIfAsleep())
                .thenReturn(Optional.of(WrapperResult.failure("Could not wake the server")));

        mockMvc.perform(post("/api/server/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Could not wake the server"));

        verify(minecraftWrapperService, never()).startServer();
        verify(alertNotificationService, never()).sendInfoAlert(anyString(), anyString());
    }

    @Test
    @DisplayName("Should not consult the wake path before credentials are validated")
    void shouldNotWakeWithBadCredentials() throws Exception {
        mockMvc.perform(post("/api/server/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wrong\",\"password\":\"wrong\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));

        verify(serverStateService, never()).wakeIfAsleep();
        verify(minecraftWrapperService, never()).startServer();
    }

    @Test
    @DisplayName("Should surface the wrapper's rejection reason when stopping the server")
    void shouldSurfaceWrapperReasonOnServerStop() throws Exception {
        when(minecraftWrapperService.stopServer())
                .thenReturn(WrapperResult.failure("Server is not running"));

        mockMvc.perform(post("/api/server/stop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Server is not running"));
    }

    @Test
    @DisplayName("Should surface the wrapper's rejection reason when restarting the server")
    void shouldSurfaceWrapperReasonOnServerRestart() throws Exception {
        when(minecraftWrapperService.restartServer())
                .thenReturn(WrapperResult.failure("Could not reach the Minecraft wrapper"));

        mockMvc.perform(post("/api/server/restart")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Could not reach the Minecraft wrapper"));
    }

    @Test
    @DisplayName("Should surface the wrapper's validation reason when a world upload is rejected")
    void shouldSurfaceWrapperReasonOnWorldUpload() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "world.zip",
            "application/zip",
            "test content".getBytes()
        );

        when(minecraftWrapperService.uploadWorld(any(), anyString()))
                .thenReturn(WrapperResult.failure("Invalid request: archive does not contain a level.dat"));

        mockMvc.perform(multipart("/api/world/upload")
                        .file(file)
                        .param("username", "admin")
                        .param("password", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error")
                        .value("Invalid request: archive does not contain a level.dat"));
    }

    // The token comes from DEPLOY_AUTH_TOKEN and must reach the wrapper unchanged — it is the
    // same secret the wrapper's POST /api/world/upload validates the bearer token against.
    @Test
    @DisplayName("Should forward the configured deploy auth token to the wrapper on world upload")
    void shouldForwardDeployAuthTokenOnWorldUpload() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "world.zip",
            "application/zip",
            "test content".getBytes()
        );

        when(minecraftWrapperService.uploadWorld(any(), anyString()))
                .thenReturn(WrapperResult.success("World uploaded"));

        mockMvc.perform(multipart("/api/world/upload")
                        .file(file)
                        .param("username", "admin")
                        .param("password", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(minecraftWrapperService).uploadWorld(any(), eq("test-deploy-token"));
    }
}
