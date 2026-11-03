package org.javaup.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiDefinition;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPathPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.api.GatewayApiMatcherManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.exception.SentinelGatewayBlockExceptionHandler;
import com.alibaba.csp.sentinel.config.SentinelConfig;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.transport.config.TransportConfig;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.javaup.gateway.sentinel.PostOnlySentinelGatewayFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;

/** Uses Sentinel's official gateway adapter and checked-in rules, without a remote rule datasource. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gateway.sentinel", name = "enabled", havingValue = "true")
public class GatewaySentinelConfiguration {

    public static final String AGENT_ADVANCE_API = "gateway-agent-advance";
    public static final String IMAGE_UPLOAD_API = "gateway-image-upload";
    private final GatewaySentinelProperties properties;

    public GatewaySentinelConfiguration(GatewaySentinelProperties properties,
                                       @Value("${spring.application.name:gateway}") String applicationName) {
        this.properties = properties;
        // Sentinel resolves app identity once on first initialization, before a request enters the adapter.
        System.setProperty(SentinelConfig.APP_TYPE_PROP_KEY,
                Integer.toString(SentinelGatewayConstants.APP_TYPE_GATEWAY));
        if (System.getProperty(SentinelConfig.APP_NAME_PROP_KEY) == null) {
            System.setProperty(SentinelConfig.APP_NAME_PROP_KEY, applicationName);
        }
        SentinelConfig.setConfig(TransportConfig.CONSOLE_SERVER, properties.getDashboard().trim());
        SentinelConfig.setConfig(TransportConfig.SERVER_PORT, Integer.toString(properties.getTransportPort()));
        SentinelConfig.setConfig(TransportConfig.HEARTBEAT_CLIENT_IP, properties.getClientIp().trim());
    }

    @PostConstruct
    void loadLocalRules() {
        // Initialize the adapter's observer before publishing the API definitions.
        GatewayApiMatcherManager.getApiMatcherMap();
        GatewayApiDefinitionManager.loadApiDefinitions(Set.of(
                new ApiDefinition(AGENT_ADVANCE_API).setPredicateItems(Set.of(
                        new ApiPathPredicateItem()
                                .setPattern("^/api/ai/v1/agent-sessions/[^/]+/advance$")
                                .setMatchStrategy(SentinelGatewayConstants.URL_MATCH_STRATEGY_REGEX))),
                new ApiDefinition(IMAGE_UPLOAD_API).setPredicateItems(Set.of(
                        new ApiPathPredicateItem().setPattern("/api/image/v1/pictures")))));
        GatewayRuleManager.loadRules(Set.of(
                qpsRule(AGENT_ADVANCE_API, properties.getAgentAdvanceQps()),
                qpsRule(IMAGE_UPLOAD_API, properties.getImageUploadQps())));
        GatewayCallbackManager.setBlockHandler((exchange, exception) -> ServerResponse
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.RETRY_AFTER, "1")
                .bodyValue(Map.of(
                        "type", "/problems/gateway-rate-limited",
                        "title", "Too Many Requests",
                        "status", 429,
                        "detail", "Gateway request rate exceeded. Retry after a short delay.",
                        "instance", exchange.getRequest().getPath().value(),
                        "code", "GATEWAY_RATE_LIMITED",
                        "traceId", UUID.randomUUID().toString())));
    }

    @Bean
    PostOnlySentinelGatewayFilter sentinelGatewayFilter() {
        return new PostOnlySentinelGatewayFilter();
    }

    @Bean
    @Order(-2)
    SentinelGatewayBlockExceptionHandler sentinelGatewayBlockExceptionHandler(
            ObjectProvider<ViewResolver> viewResolvers, ServerCodecConfigurer codecs) {
        return new SentinelGatewayBlockExceptionHandler(viewResolvers.orderedStream().toList(), codecs);
    }

    private static GatewayFlowRule qpsRule(String resource, int qps) {
        return new GatewayFlowRule(resource)
                .setResourceMode(SentinelGatewayConstants.RESOURCE_MODE_CUSTOM_API_NAME)
                .setGrade(RuleConstant.FLOW_GRADE_QPS)
                .setCount(qps)
                .setIntervalSec(1)
                .setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_DEFAULT)
                .setBurst(0);
    }
}
