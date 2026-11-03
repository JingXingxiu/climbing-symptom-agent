package org.javaup.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.javaup.payment.api.InternalPaymentController;
import org.javaup.payment.api.PaymentController;
import org.javaup.payment.api.problem.ApiProblemAdvice;
import org.javaup.payment.config.PaymentProperties;
import org.javaup.payment.mapper.PaymentOrderMapper;
import org.javaup.payment.mapper.PaymentPlanMapper;
import org.javaup.payment.mapper.UserEntitlementMapper;
import org.javaup.payment.persistence.typehandler.UuidTypeHandler;
import org.javaup.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootTest(
        properties = {
            "spring.config.location=optional:classpath:/payment-offline-test.properties",
            "spring.config.import=",
            "spring.cloud.nacos.config.enabled=false",
            "spring.cloud.nacos.config.import-check.enabled=false",
            "spring.cloud.nacos.discovery.enabled=false",
            "spring.cloud.discovery.enabled=false",
            "spring.cloud.service-registry.auto-registration.enabled=false",
            "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
        }
)
@Import(PaymentApplicationTests.OfflineTransactionConfiguration.class)
class PaymentApplicationTests {

    @MockitoBean
    private PaymentPlanMapper planMapper;

    @MockitoBean
    private PaymentOrderMapper orderMapper;

    @MockitoBean
    private UserEntitlementMapper entitlementMapper;

    @MockitoBean
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationContext context;

    @Test
    void offlineContextWiresTheApiAndTransactionalServiceWithoutEnablingMockPayment() {
        PaymentService service = context.getBean(PaymentService.class);

        assertThat(AopUtils.isAopProxy(service)).isTrue();
        assertThat(context.getBean(PaymentController.class)).isNotNull();
        assertThat(context.getBean(InternalPaymentController.class)).isNotNull();
        assertThat(context.getBean(ApiProblemAdvice.class)).isNotNull();
        assertThat(context.getBean(PaymentProperties.class).isMockEnabled()).isFalse();
        assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
    }

    @Test
    void productionYamlPointsToAUuidHandlerPackageThatMyBatisCanLoadWithoutADatabase() throws IOException {
        // DataSource exclusion intentionally skips MyBatis auto-configuration in this offline context.
        // Read the real application setting and exercise its handler scan independently.
        PropertySource<?> application = new YamlPropertySourceLoader()
                .load("payment-application", new ClassPathResource("application.yaml"))
                .getFirst();
        Object handlerPackage = application.getProperty("mybatis-plus.type-handlers-package");
        assertThat(handlerPackage).isInstanceOf(String.class);

        TypeHandlerRegistry registry = new TypeHandlerRegistry();
        registry.register((String) handlerPackage);

        assertThat(registry.getTypeHandler(UUID.class, JdbcType.OTHER))
                .isInstanceOf(UuidTypeHandler.class);
        assertThat(registry.getTypeHandler(UUID.class))
                .isInstanceOf(UuidTypeHandler.class);
        assertThat(application.getProperty("payment.mock-enabled")).isEqualTo(false);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class OfflineTransactionConfiguration {
    }
}
