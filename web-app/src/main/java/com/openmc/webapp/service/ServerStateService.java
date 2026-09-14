package com.openmc.webapp.service;

import com.openmc.webapp.kubernetes.StatefulSetScaleClient;
import com.openmc.webapp.model.ServerState;
import com.openmc.webapp.service.MinecraftWrapperService.WrapperResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Derives the {@link ServerState} shown on the dashboard.
 *
 * <p>When the dashboard is not sleep-aware (every Compose deployment, and a chart with
 * {@code webapp.sleepAware.enabled} off) this is a pass-through: the RCON online flag
 * becomes {@link ServerState#ONLINE} or {@link ServerState#OFFLINE}, exactly as before.
 *
 * <p>When it is sleep-aware, the wrapper StatefulSet's replica count is consulted
 * first: 0 replicas is {@link ServerState#ASLEEP}; a replica that is not answering yet
 * is {@link ServerState#WAKING}; anything else defers to the RCON flag. If the
 * Kubernetes API cannot be read the derivation falls back to the pass-through and says
 * so once in the log, so a mis-bound ServiceAccount degrades the dashboard to what it
 * showed before this feature existed rather than breaking the page.
 */
@Service
public class ServerStateService {

    private static final Logger log = LoggerFactory.getLogger(ServerStateService.class);

    static final String WAKE_MESSAGE = "Server is waking up — the wrapper is starting and will bring the game up";
    static final String WAKE_FAILED_MESSAGE = "Could not wake the server: the Kubernetes API rejected the scale request";

    private final StatefulSetScaleClient scaleClient;
    private final MinecraftWrapperService wrapperService;
    private volatile boolean fallbackLogged = false;

    @Autowired
    public ServerStateService(ObjectProvider<StatefulSetScaleClient> scaleClient,
                              ObjectProvider<MinecraftWrapperService> wrapperService) {
        this(scaleClient.getIfAvailable(), wrapperService.getIfAvailable());
    }

    /**
     * @param scaleClient    the Kubernetes view of the wrapper StatefulSet, or null when
     *                       the dashboard is not sleep-aware
     * @param wrapperService the wrapper client, or null when the wrapper integration is off
     */
    ServerStateService(StatefulSetScaleClient scaleClient, MinecraftWrapperService wrapperService) {
        this.scaleClient = scaleClient;
        this.wrapperService = wrapperService;
    }

    /** Whether the StatefulSet's replicas take part in the state at all. */
    public boolean isSleepAware() {
        return scaleClient != null;
    }

    /**
     * Resolve the state to show.
     *
     * @param rconOnline the RCON-derived online flag the dashboard has always used
     */
    public ServerState resolve(boolean rconOnline) {
        ServerState fallback = rconOnline ? ServerState.ONLINE : ServerState.OFFLINE;
        if (scaleClient == null) {
            return fallback;
        }
        OptionalInt replicas = scaleClient.getReplicas();
        if (replicas.isEmpty()) {
            if (!fallbackLogged) {
                log.warn("Could not read the wrapper StatefulSet's replicas from the Kubernetes API; "
                        + "showing plain wrapper status until it can be read again");
                fallbackLogged = true;
            }
            return fallback;
        }
        if (fallbackLogged) {
            log.info("The wrapper StatefulSet's replicas are readable again");
            fallbackLogged = false;
        }
        if (replicas.getAsInt() == 0) {
            return ServerState.ASLEEP;
        }
        if (!rconOnline && wrapperService != null && !wrapperService.isAvailable()) {
            return ServerState.WAKING;
        }
        return fallback;
    }

    /**
     * Scale the wrapper up if it is asleep.
     *
     * @return the outcome of the wake, or empty when the server was not asleep (or the
     *         dashboard is not sleep-aware) and the caller should start it the usual way
     */
    public Optional<WrapperResult> wakeIfAsleep() {
        if (scaleClient == null) {
            return Optional.empty();
        }
        OptionalInt replicas = scaleClient.getReplicas();
        if (replicas.isEmpty() || replicas.getAsInt() != 0) {
            return Optional.empty();
        }
        log.info("Waking the wrapper: scaling its StatefulSet to 1 replica");
        if (scaleClient.scaleTo(1)) {
            return Optional.of(WrapperResult.success(WAKE_MESSAGE));
        }
        return Optional.of(WrapperResult.failure(WAKE_FAILED_MESSAGE));
    }
}
