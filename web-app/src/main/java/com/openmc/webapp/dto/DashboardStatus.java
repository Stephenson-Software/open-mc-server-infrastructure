package com.openmc.webapp.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.openmc.webapp.model.ServerState;
import com.openmc.webapp.service.RconService;

/**
 * What {@code GET /api/status} returns: the RCON status the endpoint has always
 * served, flattened, plus the derived {@link ServerState}.
 */
public class DashboardStatus {

    @JsonUnwrapped
    private final RconService.ServerStatus status;
    private final ServerState serverState;

    public DashboardStatus(RconService.ServerStatus status, ServerState serverState) {
        this.status = status;
        this.serverState = serverState;
    }

    public RconService.ServerStatus getStatus() {
        return status;
    }

    public ServerState getServerState() {
        return serverState;
    }

    public String getServerStateMessage() {
        return serverState.getMessage();
    }
}
