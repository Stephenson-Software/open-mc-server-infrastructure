/**
 * The Minecraft server subsystem: launching, stopping and watching the server process, and
 * reading its log and memory. Built as a pure core behind ports, with one adapter per technology.
 *
 * <pre>
 *   ServerController, WorldUploadService           (callers)
 *              │
 *              ▼
 *   service.MinecraftServerService                 (composition root: binds settings, wires adapters)
 *              │
 *              ▼
 *   core.ServerLifecycle, core.ServerTelemetry,    (policy; no I/O except via ports and java.time.Clock)
 *   core.ServerCommandLine
 *              │ uses
 *              ▼
 *   ports.*  ◀──implements──  adapters.*           (one interface / one implementation per file)
 * </pre>
 *
 * <table>
 *   <caption>Ports and their adapters</caption>
 *   <tr><th>Port</th><th>Production adapter</th><th>Test fake</th></tr>
 *   <tr><td>ServerLauncher</td><td>FifoServerLauncher (mkfifo console)</td><td>FakeServerLauncher</td></tr>
 *   <tr><td>RunningServer</td><td>FifoRunningServer (Process + named pipe)</td><td>FakeRunningServer</td></tr>
 *   <tr><td>Sleeper</td><td>ThreadSleeper</td><td>FakeSleeper</td></tr>
 *   <tr><td>ServerLog</td><td>LatestLogFile (logs/latest.log)</td><td>FakeServerLog</td></tr>
 *   <tr><td>ProcessMemoryProbe</td><td>ProcfsMemoryProbe (/proc/&lt;pid&gt;/status)</td><td>FakeMemoryProbe</td></tr>
 *   <tr><td>ServerAlerts</td><td>AlertManagerServerAlerts (AlertService)</td><td>FakeServerAlerts</td></tr>
 *   <tr><td>GracefulShutdown</td><td>BroadcastGracefulShutdown (ShutdownService)</td><td>FakeGracefulShutdown</td></tr>
 *   <tr><td>java.time.Clock (JDK)</td><td>Clock.systemUTC()</td><td>MutableClock</td></tr>
 * </table>
 *
 * <p><b>Rules</b> (enforced by {@code ServerArchitectureTest}): {@code model}, {@code core} and
 * {@code ports} never import {@code adapters}, the {@code service} package or controllers; each
 * port and adapter file declares exactly one type.
 *
 * <p><b>Adding a new way to run the server</b> (say, a container or a remote host): write one
 * {@code adapters/XServerLauncher.java} plus its {@code XRunningServer.java}, and choose it in
 * {@code MinecraftServerService}'s production constructor.
 */
package com.openmc.minecraftwrapper.server;
