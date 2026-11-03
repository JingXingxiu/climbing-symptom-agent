package org.javaup.ai.model;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 平台地址和密钥只能来自服务配置；前端只能选择已配置的 provider/model。 */
@Getter
@Setter
@ConfigurationProperties("ai.models")
public class AgentModelProperties {

    private Map<String, Profile> profiles = new LinkedHashMap<>();
    private Duration timeout = Duration.ofSeconds(45);
    private int maxOutputTokens = 3000;
    private int maxInputChars = 100_000;
    private List<String> allowedImageHosts = List.of("localhost", "127.0.0.1", "minio");
    private int maxImageBytes = 5 * 1024 * 1024;
    private int maxTotalImageBytes = 16 * 1024 * 1024;

    @Getter
    @Setter
    public static class Profile {

        private String provider;
        private String model;
        private String baseUrl;
        private String apiKey;

        public Profile() {}

        public Profile(
                String provider,
                String model,
                String baseUrl,
                String apiKey
        ) {
            this.provider = provider;
            this.model = model;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
        }
    }
}
