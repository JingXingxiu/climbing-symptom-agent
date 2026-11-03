package org.javaup.ai.config;

import java.time.Clock;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@MapperScan("org.javaup.ai.mapper")
@EnableConfigurationProperties(AgentWorkflowProperties.class)
public class AgentPersistenceConfiguration {

    @Bean
    Clock agentClock() {
        return Clock.systemUTC();
    }

    @Bean
    TransactionTemplate agentTransaction(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
