package com.openmc.webapp.kubernetes;

import java.util.OptionalInt;

/**
 * The narrow view of a Kubernetes StatefulSet the dashboard needs: how many replicas
 * it is asking for, and a way to ask for a different number.
 *
 * <p>Kept as an interface so the status derivation can be tested against a fake and so
 * the rest of the webapp never sees a Kubernetes type.
 */
public interface StatefulSetScaleClient {

    /**
     * The StatefulSet's desired replica count ({@code spec.replicas}).
     *
     * @return the count, or empty when the API could not be consulted — the caller is
     *         expected to fall back to whatever it did before it knew about scaling
     */
    OptionalInt getReplicas();

    /**
     * Set the StatefulSet's desired replica count.
     *
     * @param replicas the new {@code spec.replicas}
     * @return {@code true} when the API accepted the change
     */
    boolean scaleTo(int replicas);
}
