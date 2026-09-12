package com.openmc.minecraftwrapper;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "minecraft.auto.start=false",
    // Never report a test run to the trace service.
    "usage-reporting.enabled=false"
})
class MinecraftWrapperApplicationTest {

    @Test
    void contextLoads() {
    }
}
