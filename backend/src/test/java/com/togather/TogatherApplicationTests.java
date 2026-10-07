package com.togather;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("docker-compose로 PostgreSQL을 띄운 뒤 활성화")
class TogatherApplicationTests {

    @Test
    void contextLoads() {
    }
}
