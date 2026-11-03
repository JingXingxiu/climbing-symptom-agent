package org.javaup.base.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import org.javaup.base.api.problem.ApiException;
import org.javaup.base.config.WechatProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class WechatClientTest {

    private WechatProperties properties;
    private MockRestServiceServer server;
    private WechatClient client;

    @BeforeEach
    void setUp() {
        properties = new WechatProperties();
        properties.setAppId("configured-app-id");
        properties.setSecret("server-secret");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new WechatClient(properties, builder.build());
    }

    @Test
    void missingCredentialsFailWithoutSendingTheOneUseCode() {
        properties.setSecret(null);

        ApiException exception = assertThrows(ApiException.class, () -> client.exchange("one-use-code"));

        assertFailure(exception, HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_LOGIN_NOT_CONFIGURED");
        server.verify();
    }

    @Test
    void exchangesAtOfficialEndpointAndReturnsOnlyAppIdAndOpenId() {
        server.expect(requestTo("https://api.weixin.qq.com/sns/jscode2session"
                        + "?appid=configured-app-id&secret=server-secret&js_code=one-use-code"
                        + "&grant_type=authorization_code"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"openid":"provider-open-id","session_key":"sensitive-session-key",
                         "unionid":"provider-union-id","errcode":0}
                        """, MediaType.APPLICATION_JSON));

        WechatClient.WechatSession session = client.exchange("one-use-code");

        assertEquals("configured-app-id", session.appId());
        assertEquals("provider-open-id", session.openId());
        assertEquals(Arrays.asList("appId", "openId"), Arrays.stream(session.getClass()
                .getRecordComponents()).map(component -> component.getName()).toList());
        assertFalse(session.toString().contains("sensitive-session-key"));
        assertFalse(session.toString().contains("server-secret"));
        server.verify();
    }

    @Test
    void invalidAndPreviouslyUsedCodesAreUnauthorized() {
        for (int errorCode : new int[]{40029, 40163}) {
            server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                            "https://api.weixin.qq.com/sns/jscode2session?")))
                    .andRespond(withSuccess("{\"errcode\":" + errorCode
                            + ",\"errmsg\":\"one-use-code server-secret sensitive-session-key\"}",
                            MediaType.APPLICATION_JSON));
        }
        for (int ignored : new int[]{40029, 40163}) {
            ApiException exception = assertThrows(ApiException.class,
                    () -> client.exchange("one-use-code"));

            assertFailure(exception, HttpStatus.UNAUTHORIZED, "WECHAT_CODE_INVALID");
        }
        server.verify();
    }

    @Test
    void providerFrequencyLimitHasStableRateLimitResponse() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.weixin.qq.com/")))
                .andRespond(withSuccess("{\"errcode\":45011,\"errmsg\":\"sensitive provider message\"}",
                        MediaType.APPLICATION_JSON));

        ApiException exception = assertThrows(ApiException.class, () -> client.exchange("one-use-code"));

        assertFailure(exception, HttpStatus.TOO_MANY_REQUESTS, "WECHAT_RATE_LIMITED");
        server.verify();
    }

    @Test
    void timeoutIsSanitizedAndCodeIsNotRetried() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.weixin.qq.com/")))
                .andRespond(withException(new SocketTimeoutException(
                        "https://api.weixin.qq.com/?secret=server-secret&code=one-use-code")));

        ApiException exception = assertThrows(ApiException.class, () -> client.exchange("one-use-code"));

        assertFailure(exception, HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_UNAVAILABLE");
        server.verify();
    }

    @Test
    void upstreamHttpFailureIsSanitized() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.weixin.qq.com/")))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("server-secret one-use-code sensitive-session-key"));

        ApiException exception = assertThrows(ApiException.class, () -> client.exchange("one-use-code"));

        assertFailure(exception, HttpStatus.BAD_GATEWAY, "WECHAT_UPSTREAM_ERROR");
        server.verify();
    }

    @Test
    void malformedOrIncompleteProviderResponsesAreRejected() {
        String[] invalidBodies = {"not json", "{}", "{\"openid\":\" \"}",
                "{\"errcode\":40013}", "{\"errcode\":\"0\",\"openid\":\"id\"}",
                "{\"errcode\":4294967296,\"openid\":\"id\"}"};
        for (String body : invalidBodies) {
            server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.weixin.qq.com/")))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }
        for (String ignored : invalidBodies) {
            ApiException exception = assertThrows(ApiException.class,
                    () -> client.exchange("one-use-code"));

            assertFailure(exception, HttpStatus.BAD_GATEWAY, "WECHAT_UPSTREAM_ERROR");
        }
        server.verify();
    }

    @Test
    void productionRequestFactoryAppliesFiniteConnectAndReadTimeouts() {
        properties.setConnectTimeout(Duration.ofMillis(1100));
        properties.setReadTimeout(Duration.ofMillis(2200));

        var factory = WechatClient.requestFactory(properties);

        assertEquals(1100, ReflectionTestUtils.getField(factory, "connectTimeout"));
        assertEquals(2200, ReflectionTestUtils.getField(factory, "readTimeout"));
        properties.setConnectTimeout(Duration.ZERO);
        properties.setReadTimeout(Duration.ofMillis(Long.MAX_VALUE));
        var fallbackFactory = WechatClient.requestFactory(properties);
        assertEquals(3000, ReflectionTestUtils.getField(fallbackFactory, "connectTimeout"));
        assertEquals(5000, ReflectionTestUtils.getField(fallbackFactory, "readTimeout"));
    }

    private static void assertFailure(ApiException exception, HttpStatus status, String code) {
        assertEquals(status, exception.status());
        assertEquals(code, exception.code());
        assertNull(exception.getCause());
        assertFalse(exception.toString().contains("server-secret"));
        assertFalse(exception.toString().contains("one-use-code"));
        assertFalse(exception.toString().contains("sensitive-session-key"));
        assertFalse(exception.toString().contains("https://api.weixin.qq.com/"));
    }
}
