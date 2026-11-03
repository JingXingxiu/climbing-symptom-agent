package org.javaup.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 有限步骤Agent服务；数据库迁移仍由开发者显式执行，RAG尚未启用。
 */
@SpringBootApplication
@EnableFeignClients(basePackages = "org.javaup.ai.client")
public class AIApplication {

    public static void main(String[] args) {
        SpringApplication.run(AIApplication.class, args);
    }
}
