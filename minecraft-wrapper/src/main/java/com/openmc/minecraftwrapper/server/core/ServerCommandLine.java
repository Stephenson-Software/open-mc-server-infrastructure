package com.openmc.minecraftwrapper.server.core;

import com.openmc.minecraftwrapper.server.model.ServerSettings;

import java.util.ArrayList;
import java.util.List;

/** The command that starts the Minecraft server. Pure. */
public final class ServerCommandLine {

    private ServerCommandLine() {
    }

    /** The configured java, its options, the jar. */
    public static List<String> build(ServerSettings settings) {
        List<String> command = new ArrayList<>();
        command.add(settings.javaBin());
        for (String opt : settings.javaOpts().split("\\s+")) {
            if (!opt.isEmpty()) {
                command.add(opt);
            }
        }
        command.add("-jar");
        command.add(settings.serverJar());
        command.add("nogui");
        return command;
    }
}
