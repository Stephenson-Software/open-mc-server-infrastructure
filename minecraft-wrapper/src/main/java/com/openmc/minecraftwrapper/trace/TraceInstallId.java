package com.openmc.minecraftwrapper.trace;

import java.io.File;
import java.util.Locale;
import java.util.function.Function;

/**
 * Where this program's random trace installation ID comes from (the tag
 * {@code install}, so trace can count installations rather than events).
 *
 * <p>Both values are only handed to the trace client's builder
 * ({@code installId(...)} and {@code installIdFile(...)}); the client resolves
 * them after its own opt-out checks, so a disabled client never reads or
 * writes the file. Nothing here touches the disk.
 *
 * <ol>
 *   <li>{@value #ENV}, when set and not blank, pins the ID (e.g. for a
 *       container or a service deployment);</li>
 *   <li>otherwise the file {@code <user data dir>/<program>/trace-install-id}:
 *       {@code $XDG_DATA_HOME} or {@code ~/.local/share} on Linux/BSD,
 *       {@code ~/Library/Application Support} on macOS, {@code %APPDATA%} on
 *       Windows. Delete it to get a new ID.</li>
 * </ol>
 */
public final class TraceInstallId {

    /** Environment variable that pins the installation ID. */
    public static final String ENV = "TRACE_INSTALL_ID";

    /** Name of the file the ID is kept in. */
    public static final String FILE_NAME = "trace-install-id";

    private static final int MAX_LENGTH = 255;

    private TraceInstallId() {
    }

    /** {@value #ENV}, trimmed, or {@code null} when unset, blank or too long to send. */
    public static String fromEnvironment() {
        return fromEnvironment(System::getenv);
    }

    static String fromEnvironment(Function<String, String> environment) {
        String value = environment.apply(ENV);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || trimmed.length() > MAX_LENGTH ? null : trimmed;
    }

    /** {@code <user data dir>/<programName lower-cased>/trace-install-id}. */
    public static File file(String programName) {
        return file(programName, System::getenv,
                System.getProperty("os.name", ""), System.getProperty("user.home", ""));
    }

    static File file(String programName, Function<String, String> environment, String osName, String userHome) {
        File program = new File(userDataDirectory(environment, osName, userHome),
                programName.trim().toLowerCase(Locale.ROOT));
        return new File(program, FILE_NAME);
    }

    static File userDataDirectory(Function<String, String> environment, String osName, String userHome) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            String appData = environment.apply("APPDATA");
            return notBlank(appData) ? new File(appData) : new File(new File(new File(userHome), "AppData"), "Roaming");
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return new File(new File(new File(userHome), "Library"), "Application Support");
        }
        String xdg = environment.apply("XDG_DATA_HOME");
        if (notBlank(xdg) && new File(xdg).isAbsolute()) {
            return new File(xdg);
        }
        return new File(new File(new File(userHome), ".local"), "share");
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
