package org.javaup.ai.search;

import java.net.URI;
import java.time.Duration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("ai.search")
public class SearchProperties {

    private boolean enabled;
    private URI endpoint = URI.create("https://open.bigmodel.cn/api/paas/v4/web_search");
    private String apiKey;
    private String engine = "search_std";
    private Duration timeout = Duration.ofSeconds(5);
    private int maxResults = 5;
    private int maxResponseBytes = 256 * 1024;
}
