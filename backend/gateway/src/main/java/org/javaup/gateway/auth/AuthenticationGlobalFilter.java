package org.javaup.gateway.auth;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@Component
public class AuthenticationGlobalFilter implements GlobalFilter, Ordered {

    private static final String USER_HEADER = "X-User-Id";
    private static final String LOGIN_PATH = "/api/base/v1/auth/wechat-login";
    private final JwtTokenVerifier tokenVerifier;
    private final ObjectMapper objectMapper;

    public AuthenticationGlobalFilter(JwtTokenVerifier tokenVerifier, ObjectMapper objectMapper) {
        this.tokenVerifier = tokenVerifier;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var request = exchange.getRequest();
        if (request.getMethod() == HttpMethod.POST && LOGIN_PATH.equals(request.getPath().value())) {
            return chain.filter(withUserIdentity(exchange, null));
        }
        List<String> headers = request.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (headers == null || headers.size() != 1
                || !headers.getFirst().regionMatches(true, 0, "Bearer ", 0, 7)
                || headers.getFirst().substring(7).isBlank()) {
            return problem(exchange, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
                    "A Bearer access token is required.");
        }
        UUID userId;
        try {
            userId = tokenVerifier.verify(headers.getFirst().substring(7).trim());
        } catch (IllegalStateException exception) {
            return problem(exchange, HttpStatus.SERVICE_UNAVAILABLE, "AUTH_NOT_CONFIGURED",
                    "Gateway token verification is not configured.");
        } catch (IllegalArgumentException exception) {
            return problem(exchange, HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN",
                    "Access token is invalid or expired.");
        }
        return chain.filter(withUserIdentity(exchange, userId));
    }

    private ServerWebExchange withUserIdentity(ServerWebExchange exchange, UUID userId) {
        var request = exchange.getRequest().mutate().headers(headers -> {
            // Remove all values supplied by the external caller before setting a trusted one.
            headers.remove(USER_HEADER);
            if (userId != null) {
                headers.set(USER_HEADER, userId.toString());
            }
        }).build();
        return exchange.mutate().request(request).build();
    }

    private Mono<Void> problem(ServerWebExchange exchange, HttpStatus status, String code, String detail) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        response.getHeaders().setCacheControl("no-store");
        if (status == HttpStatus.UNAUTHORIZED) {
            response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        byte[] body = objectMapper.writeValueAsBytes(Map.of(
                "type", "/problems/" + code.toLowerCase(java.util.Locale.ROOT),
                "title", status.getReasonPhrase(),
                "status", status.value(),
                "detail", detail,
                "code", code,
                "traceId", UUID.randomUUID().toString()));
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
