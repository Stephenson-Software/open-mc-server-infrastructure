package com.openmc.webapp.service;

import com.openmc.webapp.kubernetes.StatefulSetScaleClient;
import com.openmc.webapp.model.ServerState;
import com.openmc.webapp.service.MinecraftWrapperService.WrapperResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ServerStateService Tests")
class ServerStateServiceTest {

    /** A StatefulSet whose replica count (or unreachability) the test dictates. */
    private static final class FakeScaleClient implements StatefulSetScaleClient {
        OptionalInt replicas = OptionalInt.of(1);
        boolean acceptScale = true;
        final List<Integer> scaleRequests = new ArrayList<>();

        @Override
        public OptionalInt getReplicas() {
            return replicas;
        }

        @Override
        public boolean scaleTo(int replicas) {
            scaleRequests.add(replicas);
            return acceptScale;
        }
    }

    private final FakeScaleClient scaleClient = new FakeScaleClient();
    private final MinecraftWrapperService wrapperService = mock(MinecraftWrapperService.class);

    private ServerStateService sleepAware() {
        return new ServerStateService(scaleClient, wrapperService);
    }

    @Nested
    @DisplayName("when not sleep-aware")
    class NotSleepAware {

        private final ServerStateService service = new ServerStateService(null, wrapperService);

        @Test
        @DisplayName("passes the RCON flag through unchanged")
        void passesRconFlagThrough() {
            assertFalse(service.isSleepAware());
            assertEquals(ServerState.ONLINE, service.resolve(true));
            assertEquals(ServerState.OFFLINE, service.resolve(false));
        }

        @Test
        @DisplayName("never wakes, so Start goes to the wrapper as before")
        void neverWakes() {
            assertEquals(Optional.empty(), service.wakeIfAsleep());
        }
    }

    @Nested
    @DisplayName("when sleep-aware")
    class SleepAware {

        @Test
        @DisplayName("knownAsleep answers from the API alone, so callers can skip RCON")
        void knownAsleepFromApiOnly() {
            scaleClient.replicas = OptionalInt.of(0);
            assertEquals(java.util.Optional.of(true), sleepAware().knownAsleep());
            scaleClient.replicas = OptionalInt.of(1);
            assertEquals(java.util.Optional.of(false), sleepAware().knownAsleep());
            scaleClient.replicas = OptionalInt.empty();
            assertEquals(java.util.Optional.empty(), sleepAware().knownAsleep());
            assertEquals(java.util.Optional.empty(), new ServerStateService(null, wrapperService).knownAsleep());
            verify(wrapperService, never()).isAvailable();
        }

        @Test
        @DisplayName("zero replicas is ASLEEP regardless of the RCON flag")
        void zeroReplicasIsAsleep() {
            scaleClient.replicas = OptionalInt.of(0);
            ServerStateService service = sleepAware();

            assertTrue(service.isSleepAware());
            assertEquals(ServerState.ASLEEP, service.resolve(false));
            // A stale RCON cache from before the scale-down must not win over the API
            assertEquals(ServerState.ASLEEP, service.resolve(true));
            verify(wrapperService, never()).isAvailable();
        }

        @Test
        @DisplayName("one replica with the wrapper unreachable is WAKING")
        void oneReplicaUnreachableIsWaking() {
            scaleClient.replicas = OptionalInt.of(1);
            when(wrapperService.isAvailable()).thenReturn(false);

            assertEquals(ServerState.WAKING, sleepAware().resolve(false));
        }

        @Test
        @DisplayName("one replica with the wrapper answering defers to the RCON flag")
        void oneReplicaReachableUsesRcon() {
            scaleClient.replicas = OptionalInt.of(1);
            when(wrapperService.isAvailable()).thenReturn(true);
            ServerStateService service = sleepAware();

            assertEquals(ServerState.OFFLINE, service.resolve(false));
            assertEquals(ServerState.ONLINE, service.resolve(true));
        }

        @Test
        @DisplayName("an RCON-online server never asks the wrapper at all")
        void onlineSkipsWrapperProbe() {
            scaleClient.replicas = OptionalInt.of(1);

            assertEquals(ServerState.ONLINE, sleepAware().resolve(true));
            verify(wrapperService, never()).isAvailable();
        }

        @Test
        @DisplayName("an unreadable Kubernetes API falls back to the RCON flag")
        void apiFailureFallsBack() {
            scaleClient.replicas = OptionalInt.empty();
            ServerStateService service = sleepAware();

            assertEquals(ServerState.OFFLINE, service.resolve(false));
            assertEquals(ServerState.ONLINE, service.resolve(true));
            // and the fallback is decided by the API alone, not by probing the wrapper
            verify(wrapperService, never()).isAvailable();
        }

        @Test
        @DisplayName("a wrapper integration that is switched off cannot report WAKING")
        void noWrapperServiceMeansNoWaking() {
            scaleClient.replicas = OptionalInt.of(1);
            ServerStateService service = new ServerStateService(scaleClient, null);

            assertEquals(ServerState.OFFLINE, service.resolve(false));
        }
    }

    @Nested
    @DisplayName("Start when asleep")
    class Wake {

        @Test
        @DisplayName("scales the StatefulSet to 1 and reports success")
        void scalesUp() {
            scaleClient.replicas = OptionalInt.of(0);

            Optional<WrapperResult> result = sleepAware().wakeIfAsleep();

            assertTrue(result.isPresent());
            assertTrue(result.get().success());
            assertEquals(ServerStateService.WAKE_MESSAGE, result.get().message());
            assertEquals(List.of(1), scaleClient.scaleRequests);
            verify(wrapperService, never()).startServer();
        }

        @Test
        @DisplayName("reports a rejected scale request as a failure, not a crash")
        void rejectedScaleIsFailure() {
            scaleClient.replicas = OptionalInt.of(0);
            scaleClient.acceptScale = false;

            Optional<WrapperResult> result = sleepAware().wakeIfAsleep();

            assertTrue(result.isPresent());
            assertFalse(result.get().success());
            assertEquals(ServerStateService.WAKE_FAILED_MESSAGE, result.get().message());
        }

        @Test
        @DisplayName("does nothing when the wrapper is already scaled up")
        void alreadyAwake() {
            scaleClient.replicas = OptionalInt.of(1);

            assertEquals(Optional.empty(), sleepAware().wakeIfAsleep());
            assertTrue(scaleClient.scaleRequests.isEmpty());
        }

        @Test
        @DisplayName("does nothing when the Kubernetes API cannot be read")
        void apiFailureDefersToWrapper() {
            scaleClient.replicas = OptionalInt.empty();

            assertEquals(Optional.empty(), sleepAware().wakeIfAsleep());
            assertTrue(scaleClient.scaleRequests.isEmpty());
        }
    }
}
