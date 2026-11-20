package com.allanlewis.homelabwatchman;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.ai.anthropic.api-key=test-key")
class HomelabWatchmanApplicationTests {

    @Test
    void contextLoads() {
    }
}
