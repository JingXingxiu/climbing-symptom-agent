package org.javaup.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import javax.sql.DataSource;
import org.javaup.base.api.problem.ApiException;
import org.javaup.base.auth.AccessTokenService;
import org.javaup.base.client.WechatClient;
import org.javaup.base.mapper.UserAccountMapper;
import org.javaup.base.mapper.UserLoginIdentityMapper;
import org.javaup.base.mapper.UserProfileMapper;
import org.javaup.base.service.UserAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(properties = {
        "spring.config.location=optional:classpath:/base-offline-test.properties",
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.config.import-check.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "base.auth.secret=",
        "base.wechat.app-id=",
        "base.wechat.secret="
})
class BaseApplicationTests {

    @MockitoBean
    private UserAccountMapper accountMapper;

    @MockitoBean
    private UserLoginIdentityMapper identityMapper;

    @MockitoBean
    private UserProfileMapper profileMapper;

    @MockitoBean
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private AccessTokenService tokenService;

    @Autowired
    private WechatClient wechatClient;

    @Test
    void contextLoadsOfflineWithoutDatabaseWechatCredentialsOrSigningSecret() {
        assertThat(context.getBean(UserAccountService.class)).isNotNull();
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertEquals(503, assertThrows(ApiException.class, tokenService::requireConfigured).status().value());
        assertEquals(503,
                assertThrows(ApiException.class, () -> wechatClient.exchange("unused-code")).status().value());
    }

}
