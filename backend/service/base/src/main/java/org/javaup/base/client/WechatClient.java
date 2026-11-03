package org.javaup.base.client;

import java.time.Duration;
import org.javaup.base.api.problem.ApiException;
import org.javaup.base.config.WechatProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exchanges a one-use login code with WeChat. No credentials or provider body are logged. */
@Component
public class WechatClient {

    private static final String ENDPOINT = "https://api.weixin.qq.com/sns/jscode2session";
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(5);

    private final WechatProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public WechatClient(WechatProperties properties) {
        this(properties, RestClient.builder().requestFactory(requestFactory(properties)).build());
    }

    WechatClient(WechatProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    public WechatSession exchange(String code) {
        if (!hasText(properties.getAppId()) || !hasText(properties.getSecret())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_LOGIN_NOT_CONFIGURED",
                    "WeChat login is not configured.");
        }
        if (!hasText(code)) {
            throw invalidCode();
        }

        String body;
        try {
            // The URI is always server-owned; there is no retry around a one-use login code.
            var uri = UriComponentsBuilder.fromUriString(ENDPOINT)
                    .queryParam("appid", "{appId}")
                    .queryParam("secret", "{secret}")
                    .queryParam("js_code", "{code}")
                    .queryParam("grant_type", "authorization_code")
                    .encode()
                    .buildAndExpand(properties.getAppId(), properties.getSecret(), code)
                    .toUri();
            body = restClient.get().uri(uri).retrieve().body(String.class);
        } catch (ResourceAccessException exception) {
            // Transport exceptions may contain the full secret-bearing request URL: discard them.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_UNAVAILABLE",
                    "WeChat login is temporarily unavailable.");
        } catch (RestClientException exception) {
            throw upstreamError();
        }

        try {
            JsonNode response = objectMapper.readTree(body == null ? "" : body);
            if (response == null || !response.isObject()) {
                throw upstreamError();
            }
            JsonNode errorCode = response.get("errcode");
            if (errorCode != null && (!errorCode.isIntegralNumber() || !errorCode.canConvertToInt())) {
                throw upstreamError();
            }
            if (errorCode != null && errorCode.intValue() != 0) {
                throw providerFailure(errorCode.intValue());
            }
            JsonNode openId = response.get("openid");
            if (openId == null || !openId.isTextual() || !hasText(openId.asText())) {
                throw upstreamError();
            }
            // session_key and unionid never leave the parsed provider response.
            return new WechatSession(properties.getAppId(), openId.asText());
        } catch (JacksonException exception) {
            throw upstreamError();
        }
    }

    static SimpleClientHttpRequestFactory requestFactory(WechatProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(boundedTimeout(properties.getConnectTimeout(), DEFAULT_CONNECT_TIMEOUT));
        factory.setReadTimeout(boundedTimeout(properties.getReadTimeout(), DEFAULT_READ_TIMEOUT));
        return factory;
    }

    private static Duration boundedTimeout(Duration configured, Duration fallback) {
        if (configured == null || configured.isNegative() || configured.isZero()
                || configured.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0
                || configured.compareTo(Duration.ofMillis(1)) < 0) {
            return fallback;
        }
        return configured;
    }

    private static ApiException providerFailure(int errorCode) {
        if (errorCode == 40029 || errorCode == 40163) {
            return invalidCode();
        }
        if (errorCode == 45011) {
            return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "WECHAT_RATE_LIMITED",
                    "WeChat login is temporarily rate limited.");
        }
        return upstreamError();
    }

    private static ApiException invalidCode() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "WECHAT_CODE_INVALID",
                "WeChat login code is invalid or has already been used.");
    }

    private static ApiException upstreamError() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "WECHAT_UPSTREAM_ERROR",
                "WeChat login returned an invalid response.");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record WechatSession(String appId, String openId) {}
}
