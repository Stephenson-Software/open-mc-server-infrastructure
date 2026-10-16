package com.openmc.agentmanager.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmc.agentmanager.service.BackupManagerService;
import com.openmc.agentmanager.service.DiagnosticsService;
import com.openmc.agentmanager.service.MinecraftWrapperService;
import com.openmc.agentmanager.service.ToolExecutionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ToolDefinition Tests")
class ToolDefinitionTest {

    @Mock
    private MinecraftWrapperService minecraftWrapperService;

    @Mock
    private BackupManagerService backupManagerService;

    @Mock
    private DiagnosticsService diagnosticsService;

    @InjectMocks
    private ToolExecutionService toolExecutionService;

    @Test
    @DisplayName("Should advertise exactly the nine supported tools in allTools")
    void shouldAdvertiseAllSupportedTools() {
        List<String> names = ToolDefinition.allTools().stream().map(ToolDefinition::getName).toList();

        assertEquals(List.of("start_server", "stop_server", "restart_server", "get_server_status",
                "trigger_backup", "get_server_metrics", "get_activity_tracker_stats",
                "get_activity_tracker_leaderboard", "get_server_diagnostics"), names);
    }

    @Test
    @DisplayName("Should give every tool a unique name")
    void shouldGiveEveryToolAUniqueName() {
        Set<String> seen = new HashSet<>();
        for (ToolDefinition tool : ToolDefinition.allTools()) {
            assertTrue(seen.add(tool.getName()), "Duplicate tool name: " + tool.getName());
        }
    }

    @Test
    @DisplayName("Should give every tool a non-blank description")
    void shouldGiveEveryToolADescription() {
        for (ToolDefinition tool : ToolDefinition.allTools()) {
            assertNotNull(tool.getDescription(), tool.getName());
            assertFalse(tool.getDescription().isBlank(), tool.getName());
        }
    }

    @Test
    @DisplayName("Should give every tool an object input schema with properties and required list")
    void shouldGiveEveryToolAnObjectInputSchema() {
        for (ToolDefinition tool : ToolDefinition.allTools()) {
            Map<String, Object> schema = tool.getInputSchema();
            assertNotNull(schema, tool.getName());
            assertEquals("object", schema.get("type"), tool.getName());
            assertInstanceOf(Map.class, schema.get("properties"), tool.getName());
            assertInstanceOf(List.class, schema.get("required"), tool.getName());
        }
    }

    @Test
    @DisplayName("Should expose an optional integer limit property on get_server_diagnostics")
    @SuppressWarnings("unchecked")
    void shouldExposeOptionalLimitOnDiagnostics() {
        Map<String, Object> schema = ToolDefinition.getServerDiagnostics().getInputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> limit = (Map<String, Object>) properties.get("limit");

        assertNotNull(limit);
        assertEquals("integer", limit.get("type"));
        assertEquals(List.of(), schema.get("required"));
    }

    @Test
    @DisplayName("Should take no input parameters on every tool except get_server_diagnostics")
    @SuppressWarnings("unchecked")
    void shouldTakeNoInputOnParameterlessTools() {
        for (ToolDefinition tool : ToolDefinition.allTools()) {
            if (tool.getName().equals("get_server_diagnostics")) {
                continue;
            }
            Map<String, Object> properties = (Map<String, Object>) tool.getInputSchema().get("properties");
            assertTrue(properties.isEmpty(), tool.getName());
        }
    }

    @Test
    @DisplayName("Should serialize the input schema under the snake_case input_schema key")
    void shouldSerializeInputSchemaAsSnakeCase() throws Exception {
        JsonNode json = new ObjectMapper().valueToTree(ToolDefinition.startServer());

        assertTrue(json.has("input_schema"));
        assertFalse(json.has("inputSchema"));
        assertEquals("start_server", json.get("name").asText());
        assertEquals("object", json.get("input_schema").get("type").asText());
    }

    @Test
    @DisplayName("Should advertise only tools that ToolExecutionService recognizes")
    void shouldAdvertiseOnlyRecognizedTools() {
        for (ToolDefinition tool : ToolDefinition.allTools()) {
            assertTrue(toolExecutionService.isRecognizedTool(tool.getName()),
                    "Advertised tool not recognized by ToolExecutionService: " + tool.getName());
        }
    }

    @Test
    @DisplayName("Should advertise only tools that ToolExecutionService can dispatch")
    void shouldAdvertiseOnlyDispatchableTools() {
        for (ToolDefinition tool : ToolDefinition.allTools()) {
            ToolResult result = toolExecutionService.executeTool("tool-" + tool.getName(), tool.getName());

            assertTrue(result.isSuccess(),
                    "Advertised tool not dispatched by ToolExecutionService: " + tool.getName()
                            + " (" + result.getMessage() + ")");
        }
    }
}
