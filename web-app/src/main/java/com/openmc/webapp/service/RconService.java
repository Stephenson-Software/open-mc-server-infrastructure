package com.openmc.webapp.service;

import com.openmc.webapp.config.ServerConfig;
import com.openmc.webapp.model.RetrievalRecord;
import com.openmc.webapp.rcon.RconClient;
import com.openmc.webapp.repository.Repository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

@Service
public class RconService {
    
    private static final int DEFAULT_MAX_HISTORY_SIZE = 10;
    
    private final ServerConfig serverConfig;
    private final Repository<RetrievalRecord> repository;
    private ServerStatus cachedStatus;
    private Instant lastFetchTime;
    private final LinkedList<RetrievalRecord> retrievalHistory = new LinkedList<>();
    private int maxHistorySize = DEFAULT_MAX_HISTORY_SIZE;
    
    public RconService(ServerConfig serverConfig, Repository<RetrievalRecord> repository) {
        this.serverConfig = serverConfig;
        this.repository = repository;
        loadHistoricalData();
    }
    
    private void loadHistoricalData() {
        List<RetrievalRecord> loadedRecords = repository.findAll();
        if (!loadedRecords.isEmpty()) {
            // Add loaded records to history, keeping only the most recent maxHistorySize
            retrievalHistory.addAll(loadedRecords);
            while (retrievalHistory.size() > maxHistorySize) {
                retrievalHistory.removeLast();
            }
        }
    }
    
    /**
     * Get the current max history size
     * @return Current max history size
     */
    public synchronized int getMaxHistorySize() {
        return maxHistorySize;
    }
    
    /**
     * Set the max history size (session-only, not persisted)
     * @param maxHistorySize New max history size (must be > 0)
     */
    public synchronized void setMaxHistorySize(int maxHistorySize) {
        if (maxHistorySize <= 0) {
            throw new IllegalArgumentException("Max history size must be greater than 0");
        }
        
        int oldSize = this.maxHistorySize;
        this.maxHistorySize = maxHistorySize;
        
        if (maxHistorySize > oldSize && maxHistorySize > retrievalHistory.size()) {
            // If increasing size and we have fewer records than the new limit,
            // reload from repository to get more historical data
            retrievalHistory.clear();
            List<RetrievalRecord> allRecords = repository.findAll();
            retrievalHistory.addAll(allRecords);
        }
        
        // Trim history if it exceeds new size
        while (retrievalHistory.size() > maxHistorySize) {
            retrievalHistory.removeLast();
        }
    }
    
    public String sendCommand(String command) {
        try (RconClient rcon = new RconClient(serverConfig.getHost(), serverConfig.getRconPort(), 
                                              serverConfig.getRconPassword())) {
            return rcon.sendCommand(command);
        } catch (IOException e) {
            return "Error: Unable to connect to server - " + e.getMessage();
        }
    }
    
    /**
     * A status that says "offline" without attempting a connection — for a server
     * the dashboard already knows is asleep (see {@link ServerStateService#knownAsleep()}).
     * The last successful fetch's resource usage is kept so the page still has numbers.
     */
    public ServerStatus offlineStatus() {
        ResourceUsage usage = cachedStatus != null ? cachedStatus.getResourceUsage() : null;
        return new ServerStatus(serverConfig, "Error: server is asleep", usage);
    }

    public ServerStatus getServerStatus() {
        // Check if we need to refresh the cache
        if (shouldRefreshCache()) {
            refreshCache();
        }
        
        // Return cached status or fetch fresh if cache is not initialized
        if (cachedStatus == null) {
            refreshCache();
        }
        
        return cachedStatus;
    }
    
    private boolean shouldRefreshCache() {
        if (lastFetchTime == null) {
            return true;
        }
        
        long millisSinceLastFetch = Instant.now().toEpochMilli() - lastFetchTime.toEpochMilli();
        return millisSinceLastFetch >= serverConfig.getRefreshIntervalMs();
    }
    
    private void refreshCache() {
        String response = sendCommand("list");
        ResourceUsage resourceUsage = getResourceUsage();
        cachedStatus = new ServerStatus(serverConfig, response, resourceUsage);
        lastFetchTime = Instant.now();
        
        // Track retrieval in history
        boolean success = !response.startsWith("Error:");
        int playerCount = extractPlayerCount(response);
        addRetrievalRecord(new RetrievalRecord(lastFetchTime, success, playerCount, resourceUsage));
    }
    
    private int extractPlayerCount(String playerListResponse) {
        if (playerListResponse.startsWith("Error:")) {
            return 0;
        }
        
        // Extract player count from response like "There are 0 of a max of 20 players online"
        try {
            String[] parts = playerListResponse.split(" ");
            for (int i = 0; i < parts.length - 1; i++) {
                if (parts[i].equals("are") && i + 1 < parts.length) {
                    return Integer.parseInt(parts[i + 1]);
                }
            }
        } catch (Exception e) {
            // If parsing fails, return 0
        }
        return 0;
    }
    
    private synchronized void addRetrievalRecord(RetrievalRecord record) {
        retrievalHistory.addFirst(record);
        
        // Persist all records to storage (repository handles retention filtering)
        // Load all from repository first to ensure we don't lose older records
        List<RetrievalRecord> allRecords = new ArrayList<>(repository.findAll());
        // Add new record to the beginning
        allRecords.add(0, record);
        repository.save(allRecords);
        
        // Keep only the last maxHistorySize records in memory for API responses
        while (retrievalHistory.size() > maxHistorySize) {
            retrievalHistory.removeLast();
        }
    }
    
    public synchronized List<RetrievalRecord> getRetrievalHistory() {
        return Collections.unmodifiableList(new ArrayList<>(retrievalHistory));
    }
    
    // Scheduled task to fetch data every 30 minutes regardless of user visits
    @Scheduled(fixedRate = 1800000) // 30 minutes in milliseconds
    public void scheduledDataFetch() {
        // Force a cache refresh to ensure history is populated
        refreshCache();
    }
    
    public Instant getLastFetchTime() {
        return lastFetchTime;
    }
    
    public ResourceUsage getResourceUsage() {
        String tpsResponse = sendCommand("tps");
        
        // Default values when unable to fetch
        String tps = "N/A";
        String memoryUsed = "N/A";
        String memoryMax = "N/A";
        String memoryFree = "N/A";
        double memoryUsedPercent = 0.0;
        
        if (!tpsResponse.startsWith("Error:")) {
            // Parse TPS response
            // Example format: "TPS from last 1m, 5m, 15m: 20.0, 20.0, 20.0"
            if (tpsResponse.contains("TPS")) {
                tps = parseTps(tpsResponse);
            }
            
            // Parse memory information if present
            // Example format might include: "Memory: 1024MB/2048MB"
            if (tpsResponse.contains("Memory") || tpsResponse.contains("memory")) {
                String[] memoryParts = parseMemory(tpsResponse);
                if (memoryParts.length >= 3) {
                    memoryUsed = memoryParts[0];
                    memoryMax = memoryParts[1];
                    memoryFree = memoryParts[2];
                }
            }
        }
        
        // Try to get memory from forge tps command if standard tps didn't provide it
        if ("N/A".equals(memoryUsed)) {
            String forgeResponse = sendCommand("forge tps");
            if (!forgeResponse.startsWith("Error:") && forgeResponse.contains("Memory")) {
                String[] memoryParts = parseMemory(forgeResponse);
                if (memoryParts.length >= 3) {
                    memoryUsed = memoryParts[0];
                    memoryMax = memoryParts[1];
                    memoryFree = memoryParts[2];
                }
            }
        }
        
        // Calculate memory usage percentage
        if (!"N/A".equals(memoryUsed) && !"N/A".equals(memoryMax)) {
            try {
                double used = parseMemoryValue(memoryUsed);
                double max = parseMemoryValue(memoryMax);
                if (max > 0) {
                    memoryUsedPercent = (used / max) * 100.0;
                }
            } catch (Exception e) {
                // Keep default 0.0 if parsing fails
            }
        }
        
        return new ResourceUsage(tps, memoryUsed, memoryMax, memoryFree, memoryUsedPercent);
    }
    
    private String parseTps(String response) {
        // Extract TPS values from response
        // Common formats:
        // "TPS from last 1m, 5m, 15m: 20.0, 20.0, 20.0"
        // "§6TPS from last 1m, 5m, 15m: §a20.0§6, §a20.0§6, §a20.0"
        // "§6TPS from last 1m, 5m, 15m: §a*20.0, §a*20.0, §a*20.0"
        
        // Remove color codes
        String cleaned = response.replaceAll("§[0-9a-fk-or]", "");
        
        // Remove asterisks that may appear before TPS values
        cleaned = cleaned.replaceAll("\\*", "");
        
        if (cleaned.contains(":")) {
            String[] parts = cleaned.split(":");
            if (parts.length > 1) {
                // Extract just the first line if multi-line response
                String tpsLine = parts[1].split("\\n")[0].trim();
                return tpsLine;
            }
        }
        
        return response.trim();
    }
    
    private String[] parseMemory(String response) {
        // Try to extract memory information
        // Common formats:
        // "Memory: 1024MB/2048MB"
        // "Mem: 50.0% 1024MB/2048MB"
        // "Current Memory Usage: 401/2048 mb (Max: 3072 mb)"
        
        String[] result = new String[3]; // used, max, free
        result[0] = "N/A";
        result[1] = "N/A";
        result[2] = "N/A";
        
        // Limit input length to prevent ReDoS attacks
        if (response == null || response.length() > 1000) {
            return result;
        }
        
        // Remove color codes
        String cleaned = response.replaceAll("§[0-9a-fk-or]", "");
        
        // Look for pattern like "401/2048 mb" or "1024MB/2048MB" or "1024M/2048M"
        // Now handles optional space before unit and case-insensitive matching
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "(\\d+\\.?\\d*)\\s*/\\s*(\\d+\\.?\\d*)\\s*([MmGg])[Bb]?",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher matcher = pattern.matcher(cleaned);
        
        if (matcher.find()) {
            String usedStr = matcher.group(1);
            String maxStr = matcher.group(2);
            String unit = matcher.group(3).toUpperCase() + "B";
            
            result[0] = usedStr + unit;
            result[1] = maxStr + unit;
            
            // Calculate free memory
            try {
                double used = Double.parseDouble(usedStr);
                double max = Double.parseDouble(maxStr);
                double free = max - used;
                result[2] = String.format("%.1f%s", free, unit);
            } catch (Exception e) {
                result[2] = "N/A";
            }
        }
        
        return result;
    }
    
    private double parseMemoryValue(String memoryStr) {
        // Parse memory string like "1024MB" or "2.5GB" to MB
        if (memoryStr == null || memoryStr.equals("N/A")) {
            return 0.0;
        }
        
        String cleaned = memoryStr.replaceAll("[^0-9.]", "");
        double value = Double.parseDouble(cleaned);
        
        if (memoryStr.contains("GB") || memoryStr.contains("G")) {
            value *= 1024; // Convert GB to MB
        }
        
        return value;
    }
    
    public static class ServerStatus {
        private final String motd;
        private final int maxPlayers;
        private final String playerList;
        private final boolean online;
        private final ResourceUsage resourceUsage;
        
        public ServerStatus(ServerConfig config, String playerListResponse, ResourceUsage resourceUsage) {
            this.motd = config.getMotd();
            this.maxPlayers = config.getMaxPlayers();
            this.playerList = playerListResponse;
            this.online = !playerListResponse.startsWith("Error:");
            this.resourceUsage = resourceUsage;
        }
        
        public String getMotd() {
            return motd;
        }
        
        public int getMaxPlayers() {
            return maxPlayers;
        }
        
        public String getPlayerList() {
            return playerList;
        }
        
        public boolean isOnline() {
            return online;
        }
        
        public ResourceUsage getResourceUsage() {
            return resourceUsage;
        }
    }
    
    public static class ResourceUsage {
        private final String tps;
        private final String memoryUsed;
        private final String memoryMax;
        private final String memoryFree;
        private final double memoryUsedPercent;
        
        @com.fasterxml.jackson.annotation.JsonCreator
        public ResourceUsage(
                @com.fasterxml.jackson.annotation.JsonProperty("tps") String tps, 
                @com.fasterxml.jackson.annotation.JsonProperty("memoryUsed") String memoryUsed, 
                @com.fasterxml.jackson.annotation.JsonProperty("memoryMax") String memoryMax, 
                @com.fasterxml.jackson.annotation.JsonProperty("memoryFree") String memoryFree, 
                @com.fasterxml.jackson.annotation.JsonProperty("memoryUsedPercent") double memoryUsedPercent) {
            this.tps = tps;
            this.memoryUsed = memoryUsed;
            this.memoryMax = memoryMax;
            this.memoryFree = memoryFree;
            this.memoryUsedPercent = memoryUsedPercent;
        }
        
        public String getTps() {
            return tps;
        }
        
        public String getMemoryUsed() {
            return memoryUsed;
        }
        
        public String getMemoryMax() {
            return memoryMax;
        }
        
        public String getMemoryFree() {
            return memoryFree;
        }
        
        public double getMemoryUsedPercent() {
            return memoryUsedPercent;
        }
    }
}
