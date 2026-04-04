package com.mymedroads.bot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "anthropic.api-key=test-key"
})
class MymedroadsBotApplicationTests {

    @Test
    void contextLoads() {
    }
}
