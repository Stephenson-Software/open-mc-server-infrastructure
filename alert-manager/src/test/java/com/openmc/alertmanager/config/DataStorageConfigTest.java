package com.openmc.alertmanager.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DataStorageConfig Tests")
class DataStorageConfigTest {

    // ConfigurationPropertiesAutoConfiguration supplies the binding post-processor a
    // running Boot application gets, so @ConfigurationProperties is honoured here too.
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(DataStorageConfiguration.class);

    private DataStorageConfig configWithBase(String baseDirectory) {
        DataStorageConfig config = new DataStorageConfig();
        config.setBaseDirectory(baseDirectory);
        return config;
    }

    @Test
    @DisplayName("Should default the base directory to /app/data, the mount point of the alert-manager-data volume")
    void shouldDefaultBaseDirectory() {
        assertEquals("/app/data", new DataStorageConfig().getBaseDirectory());
    }

    @Test
    @DisplayName("Should join the base directory and filename with a separator")
    void shouldJoinBaseAndFilename() {
        assertEquals("/srv/data/alert-history.json", configWithBase("/srv/data").getFilePath("alert-history.json"));
    }

    @Test
    @DisplayName("Should not double the separator when the base directory ends with one")
    void shouldNotDoubleTrailingSeparator() {
        assertEquals("/srv/data/alert-history.json", configWithBase("/srv/data/").getFilePath("alert-history.json"));
    }

    @Test
    @DisplayName("Should keep a relative base directory relative")
    void shouldKeepRelativeBaseRelative() {
        assertEquals("data/alert-history.json", configWithBase("data").getFilePath("alert-history.json"));
    }

    @Test
    @DisplayName("Should bind data.storage.base-directory onto the bean")
    void shouldBindBaseDirectoryProperty() {
        contextRunner
                .withPropertyValues("data.storage.base-directory=/srv/omcsi")
                .run(context -> {
                    DataStorageConfig config = context.getBean(DataStorageConfig.class);
                    assertEquals("/srv/omcsi", config.getBaseDirectory());
                    assertEquals("/srv/omcsi/alert-history.json", config.getFilePath("alert-history.json"));
                });
    }

    @Test
    @DisplayName("Should keep the default base directory when the property is unset")
    void shouldKeepDefaultWhenPropertyUnset() {
        contextRunner.run(context ->
                assertEquals("/app/data", context.getBean(DataStorageConfig.class).getBaseDirectory()));
    }
}
