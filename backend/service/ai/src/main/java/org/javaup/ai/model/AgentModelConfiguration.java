package org.javaup.ai.model;

import org.javaup.ai.search.SearchProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 手动构造选中的模型，不要求应用启动时所有平台都有可用密钥。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AgentModelProperties.class, SearchProperties.class})
public class AgentModelConfiguration {}
