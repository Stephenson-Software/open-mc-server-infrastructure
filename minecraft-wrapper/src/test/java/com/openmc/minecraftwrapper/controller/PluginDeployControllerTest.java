package com.openmc.minecraftwrapper.controller;

import com.openmc.minecraftwrapper.service.AlertService;
import com.openmc.minecraftwrapper.service.PluginDeployService;
import com.openmc.minecraftwrapper.service.WebAppNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PluginDeployController Tests")
class PluginDeployControllerTest {

    private static final String VALID_TOKEN = "test-secret-token";
    private static final String DEPLOY_URL = "/api/plugins/deploy";

    @Mock
    private PluginDeployService pluginDeployService;

    @Mock
    private AlertService alertService;

    @Mock
    private WebAppNotificationService webAppNotificationService;

    @InjectMocks
    private PluginDeployController pluginDeployController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(pluginDeployController, "deployAuthToken", VALID_TOKEN);
        mockMvc = MockMvcBuilders.standaloneSetup(pluginDeployController).build();
    }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Should deploy plugin and return 200 OK with valid token")
    void shouldDeployPluginWithValidToken() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "MyPlugin.jar",
                "application/java-archive", "jar-content".getBytes());

        mockMvc.perform(multipart(DEPLOY_URL)
                        .file(file)
                        .param("pluginName", "MyPlugin.jar")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string("Plugin deployed successfully"));

        verify(pluginDeployService, times(1)).replacePlugin(eq("MyPlugin.jar"), any());
        verify(alertService, times(1)).sendPluginDeploySuccessAlert("MyPlugin.jar", null, null);
    }

    @Test
    @DisplayName("Should include branch and repoUrl in success alert when provided")
    void shouldForwardBranchAndRepoUrlInSuccessAlert() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "MyPlugin.jar",
                "application/java-archive", "jar-content".getBytes());

        mockMvc.perform(multipart(DEPLOY_URL)
                        .file(file)
                        .param("pluginName", "MyPlugin.jar")
                        .param("branch", "main")
                        .param("repoUrl", "https://github.com/org/repo")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk());

        verify(alertService, times(1))
                .sendPluginDeploySuccessAlert("MyPlugin.jar", "main", "https://github.com/org/repo");
    }

    // ── authentication ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Should return 401 when Authorization header is missing")
    void shouldReturn401WhenAuthHeaderMissing() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "MyPlugin.jar",
                "application/java-archive", "jar-content".getBytes());

        mockMvc.perform(multipart(DEPLOY_URL)
                        .file(file)
                        .param("pluginName", "MyPlugin.jar"))
                .andExpect(status().isUnauthorized());

        verify(pluginDeployService, never()).replacePlugin(any(), any());
    }

    @Test
    @DisplayName("Should return 401 when token is wrong")
    void shouldReturn401WhenTokenIsWrong() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "MyPlugin.jar",
                "application/java-archive", "jar-content".getBytes());

        mockMvc.perform(multipart(DEPLOY_URL)
                        .file(file)
                        .param("pluginName", "MyPlugin.jar")
                        .header("Authorization", "Bearer wrong-token"))
                .andExpect(status().isUnauthorized());

        verify(pluginDeployService, never()).replacePlugin(any(), any());
    }

    @Test
    @DisplayName("Should return 401 when token is not configured")
    void shouldReturn401WhenTokenNotConfigured() throws Exception {
        ReflectionTestUtils.setField(pluginDeployController, "deployAuthToken", "");

        MockMultipartFile file = new MockMultipartFile("file", "MyPlugin.jar",
                "application/java-archive", "jar-content".getBytes());

        mockMvc.perform(multipart(DEPLOY_URL)
                        .file(file)
                        .param("pluginName", "MyPlugin.jar")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isUnauthorized());

        verify(pluginDeployService, never()).replacePlugin(any(), any());
    }

    @Test
    @DisplayName("Should return 401 when Authorization header has no Bearer prefix")
    void shouldReturn401WhenNoBearerPrefix() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "MyPlugin.jar",
                "application/java-archive", "jar-content".getBytes());

        mockMvc.perform(multipart(DEPLOY_URL)
                        .file(file)
                        .param("pluginName", "MyPlugin.jar")
                        .header("Authorization", VALID_TOKEN))
                .andExpect(status().isUnauthorized());

        verify(pluginDeployService, never()).replacePlugin(any(), any());
    }

    // ── service error handling ────────────────────────────────────────────────

    @Test
    @DisplayName("Should return 400 when service throws IllegalArgumentException")
    void shouldReturn400OnIllegalArgument() throws Exception {
        doThrow(new IllegalArgumentException("Plugin name must end with .jar"))
                .when(pluginDeployService).replacePlugin(any(), any());

        MockMultipartFile file = new MockMultipartFile("file", "bad.zip",
                "application/octet-stream", "zip-content".getBytes());

        mockMvc.perform(multipart(DEPLOY_URL)
                        .file(file)
                        .param("pluginName", "bad.zip")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid request"));

        verify(alertService, times(1)).sendPluginDeployFailureAlert(eq("bad.zip"), anyString(), isNull(), isNull());
    }

    @Test
    @DisplayName("Should return 500 when service throws IOException")
    void shouldReturn500OnIOException() throws Exception {
        doThrow(new IOException("Plugins directory does not exist"))
                .when(pluginDeployService).replacePlugin(any(), any());

        MockMultipartFile file = new MockMultipartFile("file", "MyPlugin.jar",
                "application/java-archive", "jar-content".getBytes());

        mockMvc.perform(multipart(DEPLOY_URL)
                        .file(file)
                        .param("pluginName", "MyPlugin.jar")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isInternalServerError());

        verify(alertService, times(1)).sendPluginDeployFailureAlert(eq("MyPlugin.jar"), anyString(), isNull(), isNull());
    }

    // ── applied report ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Should serve the plugin selection report without a token")
    void shouldServeAppliedReport() throws Exception {
        String report = "{\"plugins\":{\"fiefs\":\"installed\"}}";
        when(pluginDeployService.readAppliedReport()).thenReturn(Optional.of(report));

        mockMvc.perform(get("/api/plugins/applied"))
                .andExpect(status().isOk())
                .andExpect(content().json(report));
    }

    @Test
    @DisplayName("Should return 404 when no plugin selection has been reconciled")
    void shouldReturnNotFoundWithoutAppliedReport() throws Exception {
        when(pluginDeployService.readAppliedReport()).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/plugins/applied"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Should return 500 when the plugin selection report cannot be read")
    void shouldReturnServerErrorWhenAppliedReportUnreadable() throws Exception {
        when(pluginDeployService.readAppliedReport()).thenThrow(new IOException("disk"));

        mockMvc.perform(get("/api/plugins/applied"))
                .andExpect(status().isInternalServerError());
    }
}
