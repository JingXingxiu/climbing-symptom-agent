package org.javaup.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.alibaba.csp.sentinel.adapter.gateway.sc.exception.SentinelGatewayBlockExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class GatewaySentinelPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void defaultConfigurationLeavesSentinelDisabledAndKeepsDemonstrationThresholds() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            GatewaySentinelProperties properties = context.getBean(GatewaySentinelProperties.class);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.getAgentAdvanceQps()).isEqualTo(2);
            assertThat(properties.getImageUploadQps()).isEqualTo(5);
            assertThat(properties.getTransportPort()).isEqualTo(8719);
            assertThat(properties.getDashboard()).isEmpty();
            assertThat(context).doesNotHaveBean(SentinelGatewayFilter.class);
            assertThat(context).doesNotHaveBean(SentinelGatewayBlockExceptionHandler.class);
        });
    }

    @Test
    void explicitDisableDoesNotInstallTheAdapterOrExceptionHandler() {
        runner.withPropertyValues("gateway.sentinel.enabled=false",
                "gateway.sentinel.agent-advance-qps=7", "gateway.sentinel.image-upload-qps=9")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(GatewaySentinelProperties.class).getAgentAdvanceQps()).isEqualTo(7);
                    assertThat(context).doesNotHaveBean(SentinelGatewayFilter.class);
                    assertThat(context).doesNotHaveBean(SentinelGatewayBlockExceptionHandler.class);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "gateway.sentinel.agent-advance-qps=0",
            "gateway.sentinel.image-upload-qps=-1",
            "gateway.sentinel.transport-port=0",
            "gateway.sentinel.transport-port=65536"
    })
    void rejectsInvalidThresholdsAndTransportPorts(String invalidProperty) {
        runner.withPropertyValues(invalidProperty).run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GatewaySentinelProperties.class)
    @Import(GatewaySentinelConfiguration.class)
    static class PropertiesConfiguration {
    }
}
