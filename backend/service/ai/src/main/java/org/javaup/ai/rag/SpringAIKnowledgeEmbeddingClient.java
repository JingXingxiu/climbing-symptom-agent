package org.javaup.ai.rag;

import java.util.Map;

import io.micrometer.observation.ObservationRegistry;
import org.javaup.ai.observability.AgentTelemetry;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.stereotype.Component;

/** 复用 Spring AI 的 OpenAI 兼容协议适配器，支持配置国产 embedding 平台。 */
@Component
public class SpringAIKnowledgeEmbeddingClient implements KnowledgeEmbeddingClient {
    private final KnowledgeRetrievalProperties properties;
    private final ObservationRegistry observations;
    private final AgentTelemetry telemetry;
    private volatile OpenAiEmbeddingModel client;

    public SpringAIKnowledgeEmbeddingClient(
            KnowledgeRetrievalProperties properties,
            ObservationRegistry observations,
            AgentTelemetry telemetry
    ) {
        this.properties = properties;
        this.observations = observations;
        this.telemetry = telemetry;
    }

    @Override
    public float[] embed(String publicQuery) {
        return telemetry.observe(
                "knowledge_embedding",
                Map.of("provider", properties.getProvider(), "model", properties.getModel()),
                () -> client().embed(publicQuery));
    }

    private OpenAiEmbeddingModel client() {
        OpenAiEmbeddingModel configured = client;
        if (configured != null) {
            return configured;
        }
        synchronized (this) {
            if (client == null) {
                OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                        .baseUrl(properties.getBaseUrl())
                        .apiKey(properties.getApiKey())
                        .model(properties.getModel())
                        .dimensions(properties.isSendDimensions() ? properties.getDimensions() : null)
                        .maxRetries(0)
                        .timeout(properties.getTimeout())
                        .build();
                client = OpenAiEmbeddingModel.builder()
                        .options(options)
                        .observationRegistry(observations)
                        .build();
            }
            return client;
        }
    }
}
