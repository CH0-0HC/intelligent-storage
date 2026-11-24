package com.ai.smartinventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Spring Boot 应用上下文集成测试
 * 验证应用能够正常启动和加载所有Bean
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("应用上下文集成测试")
class SmartInventoryApplicationTests {

    @Test
    @DisplayName("应用上下文加载成功")
    void contextLoads() {
        // 验证Spring容器能够正常启动
    }
}