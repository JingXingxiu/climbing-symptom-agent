package org.javaup.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.javaup.ai.mapper.*;
import org.javaup.ai.client.AgentPaymentClient;
import org.javaup.ai.service.AgentService;
import org.javaup.ai.rag.KnowledgeRetrievalMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@SpringBootTest(
        properties = {
            "spring.config.location=optional:classpath:/ai-offline-test.properties",
            "spring.config.import=",
            "spring.cloud.nacos.config.enabled=false",
            "spring.cloud.nacos.config.import-check.enabled=false",
            "spring.cloud.nacos.discovery.enabled=false",
            "spring.cloud.discovery.enabled=false",
            "spring.cloud.service-registry.auto-registration.enabled=false",
            "management.tracing.export.otlp.enabled=false",
            "management.logging.export.otlp.enabled=false",
            "management.otlp.metrics.export.enabled=false",
            "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration,org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration,org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration,org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration,org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration,org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration"
        })
class AIApplicationTests {
    @MockitoBean AgentSessionMapper sessions;
    @MockitoBean AgentStepMapper steps;
    @MockitoBean AgentCheckpointMapper checkpoints;
    @MockitoBean ModelCallMapper calls;
    @MockitoBean UserDailyUsageMapper usage;
    @MockitoBean KnowledgeRetrievalMapper knowledge;
    @MockitoBean AgentPaymentClient payment;
    @MockitoBean PlatformTransactionManager transactionManager;
    @Autowired ApplicationContext context;

    @Test
    void offlineContextNeedsNeitherModelCredentialsNorLiveDatabase() {
        assertThat(context.getBean(AgentService.class)).isNotNull();
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
    }
}
