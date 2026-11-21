package com.openmc.minecraftwrapper.server.adapters;

import com.openmc.minecraftwrapper.server.ports.ServerAlerts;
import com.openmc.minecraftwrapper.service.AlertService;

/** Sends lifecycle alerts to the alert-manager service through {@link AlertService}. */
public final class AlertManagerServerAlerts implements ServerAlerts {

    private final AlertService alertService;

    public AlertManagerServerAlerts(AlertService alertService) {
        this.alertService = alertService;
    }

    @Override
    public void serverStarted() {
        alertService.sendServerStartAlert();
    }

    @Override
    public void serverStopped() {
        alertService.sendServerStopAlert();
    }

    @Override
    public void serverCrashed(int exitCode) {
        alertService.sendServerCrashAlert(exitCode);
    }
}
