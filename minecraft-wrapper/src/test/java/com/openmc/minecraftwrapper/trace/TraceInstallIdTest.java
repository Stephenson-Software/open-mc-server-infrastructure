package com.openmc.minecraftwrapper.trace;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TraceInstallIdTest {

    @Test
    void environmentPinsTheIdTrimmedAndBlankOrOverlongMeansNone() {
        Map<String, String> env = new HashMap<>();
        assertNull(TraceInstallId.fromEnvironment(env::get));
        env.put("TRACE_INSTALL_ID", "   ");
        assertNull(TraceInstallId.fromEnvironment(env::get));
        env.put("TRACE_INSTALL_ID", "  pinned-1  ");
        assertEquals("pinned-1", TraceInstallId.fromEnvironment(env::get));
        StringBuilder overlong = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            overlong.append('x');
        }
        env.put("TRACE_INSTALL_ID", overlong.toString());
        assertNull(TraceInstallId.fromEnvironment(env::get), "the file is used instead");
    }

    @Test
    void fileLivesInThePlatformsUserDataDirectoryUnderTheLowerCasedProgramName() {
        Map<String, String> env = new HashMap<>();
        File home = new File("/home/someone");
        assertEquals(new File(home, ".local/share/myprogram/trace-install-id"),
                TraceInstallId.file("MyProgram", env::get, "Linux", home.getPath()));
        env.put("XDG_DATA_HOME", "/data");
        assertEquals(new File("/data/myprogram/trace-install-id"),
                TraceInstallId.file("MyProgram", env::get, "Linux", home.getPath()));
        env.put("XDG_DATA_HOME", "relative/ignored");
        assertEquals(new File(home, ".local/share/myprogram/trace-install-id"),
                TraceInstallId.file("MyProgram", env::get, "FreeBSD", home.getPath()));
        assertEquals(new File(home, "Library/Application Support/myprogram/trace-install-id"),
                TraceInstallId.file("MyProgram", env::get, "Mac OS X", home.getPath()));
        env.put("APPDATA", "C:\\Users\\someone\\AppData\\Roaming");
        assertEquals(new File(new File("C:\\Users\\someone\\AppData\\Roaming", "myprogram"), "trace-install-id"),
                TraceInstallId.file("MyProgram", env::get, "Windows 11", home.getPath()));
    }
}
