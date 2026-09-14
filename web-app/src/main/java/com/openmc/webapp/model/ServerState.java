package com.openmc.webapp.model;

/**
 * What the dashboard says about the server, one level above the raw RCON "online"
 * flag. {@link #ONLINE} and {@link #OFFLINE} are what every deployment has always
 * shown; {@link #ASLEEP} and {@link #WAKING} only occur when the dashboard runs
 * sleep-aware on Kubernetes, where a wrapper scaled to zero replicas is a resting
 * server rather than a broken one.
 */
public enum ServerState {

    /** The game answered RCON. */
    ONLINE("Online", "The server is running."),

    /** The game did not answer RCON and, as far as the dashboard knows, ought to have. */
    OFFLINE("Offline", "The server is not running."),

    /** The wrapper's StatefulSet rests at 0 replicas: nothing is wrong, nobody is on. */
    ASLEEP("Asleep", "Asleep — join to wake, or press Start"),

    /** The StatefulSet has been scaled up but the wrapper is not answering yet. */
    WAKING("Waking", "Waking up — the server is starting");

    private final String label;
    private final String message;

    ServerState(String label, String message) {
        this.label = label;
        this.message = message;
    }

    /** Short word for a status indicator. */
    public String getLabel() {
        return label;
    }

    /** One line for a status panel. */
    public String getMessage() {
        return message;
    }
}
