package org.javaup.ai.rag;

import java.time.Duration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 只读检索开关与独立的 embedding 配置；不复用聊天模型的名称或密钥。 */
@Getter
@Setter
@ConfigurationProperties("ai.rag")
public class KnowledgeRetrievalProperties {
    private boolean enabled;
    private String provider;
    private String model;
    private int dimensions;
    private String baseUrl;
    private String apiKey;
    private Duration timeout = Duration.ofSeconds(10);
    private boolean sendDimensions = true;
    private int topK = 3;
    private double minScore = 0.65;
    private int maxContentChars = 1600;

    /** 库中 embedding_model 使用此完整身份，避免不同平台同名模型混算。 */
    public String embeddingIdentity() {
        return provider + ":" + model;
    }
}
