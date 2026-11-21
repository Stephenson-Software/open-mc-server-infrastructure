package com.openmc.minecraftwrapper.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Keeps the server subsystem's layers honest (no ArchUnit: plain source scan, no new dependency).
 *
 * <ul>
 *   <li>{@code model}, {@code core} and {@code ports} never import {@code server.adapters}, the
 *       composition root, or anything else in {@code com.openmc.minecraftwrapper.service}.</li>
 *   <li>{@code model} and {@code ports} import nothing from {@code core}.</li>
 *   <li>Each port and adapter file declares exactly one top-level type.</li>
 * </ul>
 */
@DisplayName("server/ architecture")
class ServerArchitectureTest {

    private static final Path ROOT = Path.of("src/main/java/com/openmc/minecraftwrapper/server");
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+)", Pattern.MULTILINE);
    private static final Pattern TOP_LEVEL_TYPE = Pattern.compile(
            "^(?:public\\s+|final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+)*(?:class|interface|record|enum|@interface)\\s+\\w+",
            Pattern.MULTILINE);

    private static List<Path> javaFiles(String layer) throws IOException {
        try (Stream<Path> files = Files.walk(ROOT.resolve(layer))) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static List<String> violations(String layer, String... forbiddenPrefixes) throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : javaFiles(layer)) {
            Matcher m = IMPORT.matcher(Files.readString(file));
            while (m.find()) {
                for (String forbidden : forbiddenPrefixes) {
                    if (m.group(1).startsWith(forbidden)) {
                        found.add(file.getFileName() + " imports " + m.group(1));
                    }
                }
            }
        }
        return found;
    }

    @Test
    void innerLayersDoNotDependOnOuterOnes() throws IOException {
        String[] outer = {
                "com.openmc.minecraftwrapper.server.adapters",
                "com.openmc.minecraftwrapper.service",
                "com.openmc.minecraftwrapper.controller",
        };
        List<String> found = new ArrayList<>();
        for (String layer : List.of("model", "core", "ports")) {
            found.addAll(violations(layer, outer));
        }
        found.addAll(violations("model", "com.openmc.minecraftwrapper.server.core", "com.openmc.minecraftwrapper.server.ports"));
        found.addAll(violations("ports", "com.openmc.minecraftwrapper.server.core"));
        assertEquals(List.of(), found);
    }

    @Test
    void oneTypePerPortAndAdapterFile() throws IOException {
        List<String> found = new ArrayList<>();
        for (String layer : List.of("ports", "adapters")) {
            for (Path file : javaFiles(layer)) {
                long types = TOP_LEVEL_TYPE.matcher(Files.readString(file)).results().count();
                if (types != 1) {
                    found.add(file.getFileName() + " declares " + types + " top-level types");
                }
            }
        }
        assertEquals(List.of(), found);
    }
}
