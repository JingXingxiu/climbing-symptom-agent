package org.javaup.ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("ai.workflow")
public class AgentWorkflowProperties {

    private String locale = "zh-CN";

    private String defaultProvider = "zhipu";
    private String defaultModel = "glm-4.6v-flash";

    private int maxModelCalls = 20;
    private int maxSteps = 40;
    private int executionTimeoutSeconds = 120;
}
