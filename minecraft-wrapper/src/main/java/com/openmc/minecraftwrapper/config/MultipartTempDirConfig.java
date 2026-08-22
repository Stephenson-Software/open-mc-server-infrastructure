package com.openmc.minecraftwrapper.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Creates the configured multipart buffer directory at startup.
 *
 * <p>Tomcat rejects a multipart location that does not exist, and it does so on the first
 * upload rather than at boot — so a mistyped or unmounted {@code MULTIPART_TEMP_DIR} would
 * otherwise surface as a failed upload long after deploy. Creating it here turns that into a
 * startup-time log line instead.
 *
 * <p>Does nothing when the property is empty, which is the default: uploads then buffer into
 * the servlet container's own temp directory.
 */
@Slf4j
@Configuration
public class MultipartTempDirConfig {

    @Value("${spring.servlet.multipart.location:}")
    private String multipartLocation;

    @PostConstruct
    void createMultipartTempDir() {
        if (multipartLocation == null || multipartLocation.isBlank()) {
            return;
        }
        Path dir = Paths.get(multipartLocation);
        try {
            Files.createDirectories(dir);
            log.info("Buffering multipart uploads in {}", dir);
        } catch (IOException e) {
            log.error("Could not create multipart upload directory {}: {}. Uploads will fail until "
                    + "this path exists and is writable, or MULTIPART_TEMP_DIR is cleared.", dir, e.getMessage(), e);
        }
    }
}
