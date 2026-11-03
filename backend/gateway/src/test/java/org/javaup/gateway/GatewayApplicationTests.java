package org.javaup.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.javaup.gateway.config.GatewayAuthProperties;
import org.javaup.gateway.config.GatewaySentinelProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/** Exercises the running Gateway and its HTTP forwarding without discovery or external services. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.location=optional:classpath:/gateway-test.properties")
@Execution(ExecutionMode.SAME_THREAD)
class GatewayApplicationTests {

    private static final String SECRET = "gateway-integration-only-signing-secret-at-least-32-bytes";
    private static final String USER_ID = "b81cb813-fc17-4c26-9cce-96884207020c";
    private static final String ISSUER = "climbing-base-service";
    private static final String AUDIENCE = "climbing-api";
    private static final BlockingQueue<ForwardedRequest> FORWARDED = new LinkedBlockingQueue<>();
    private static final AtomicInteger UPSTREAM_REQUESTS = new AtomicInteger();
    private static final HttpServer UPSTREAM = startUpstream();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GatewayAuthProperties authProperties;

    @Autowired
    private GatewaySentinelProperties sentinelProperties;

    @DynamicPropertySource
    static void configureGateway(DynamicPropertyRegistry registry) {
        registry.add("gateway.auth.secret", () -> SECRET);
        registry.add("gateway.auth.issuer", () -> ISSUER);
        registry.add("gateway.auth.audience", () -> AUDIENCE);
        String[] services = {"base", "image", "history", "ai"};
        for (int index = 0; index < services.length; index++) {
            String service = services[index];
            String route = "spring.cloud.gateway.server.webflux.routes[" + index + "].";
            registry.add(route + "id", () -> service + "-service");
            registry.add(route + "uri", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
            registry.add(route + "predicates[0]", () -> "Path=/api/" + service + "/**");
        }
    }

    @BeforeEach
    void clearUpstreamRequests() {
        FORWARDED.clear();
        UPSTREAM_REQUESTS.set(0);
    }

    @AfterAll
    static void stopUpstream() {
        CLIENT.close();
        UPSTREAM.stop(0);
    }

    @Test
    void missingBearerTokenReturnsProblemWithoutCallingUpstream() throws Exception {
        String path = "/api/history/v1/history";

        HttpResponse<String> response = send(request(path).GET().build());

        assertProblem(response, 401);
        assertEquals(0, UPSTREAM_REQUESTS.get());
    }

    @Test
    void disabledSentinelDoesNotLimitTheTargetApi() throws Exception {
        assertFalse(sentinelProperties.isEnabled());
        for (int index = 0; index < 8; index++) {
            HttpResponse<String> response = send(request("/api/ai/v1/agent-sessions/session-one/advance")
                    .header("Authorization", "Bearer " + validToken())
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build());
            assertEquals(200, response.statusCode());
        }
        assertEquals(8, UPSTREAM_REQUESTS.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"base", "image", "history", "ai"})
    void authenticatedPublicRoutesPreservePathAndReplaceUntrustedIdentity(String service) throws Exception {
        String target = "/api/" + service + "/v1/resource/nested?view=summary&limit=2";
        String authorization = "Bearer " + validToken();

        HttpResponse<String> response = send(request(target)
                .header("Authorization", authorization)
                .header("X-User-Id", "external-user-id")
                .GET().build());

        assertEquals(200, response.statusCode());
        ForwardedRequest forwarded = takeForwardedRequest();
        assertEquals("GET", forwarded.method());
        assertEquals(target, forwarded.target());
        assertEquals(List.of(USER_ID), forwarded.userIds());
        assertEquals(List.of(authorization), forwarded.authorization());
        assertEquals(1, UPSTREAM_REQUESTS.get());
    }

    @Test
    void duplicateUntrustedIdentityHeadersBecomeOneVerifiedIdentity() throws Exception {
        HttpResponse<String> response = send(request("/api/image/v1/images")
                .header("Authorization", "Bearer " + validToken())
                .header("X-User-Id", "attacker-one")
                .header("X-User-Id", "attacker-two")
                .GET().build());

        assertEquals(200, response.statusCode());
        assertEquals(List.of(USER_ID), takeForwardedRequest().userIds());
        assertEquals(1, UPSTREAM_REQUESTS.get());
    }

    @Test
    void exactLoginPostNeedsNoBearerTokenAndStripsExternalIdentity() throws Exception {
        String target = "/api/base/v1/auth/wechat-login?client=mobile";
        String body = "{\"code\":\"local-stub-code\"}";

        HttpResponse<String> response = send(request(target)
                .header("Content-Type", "application/json")
                .header("X-User-Id", "attacker-one")
                .header("X-User-Id", "attacker-two")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());

        assertEquals(200, response.statusCode());
        ForwardedRequest forwarded = takeForwardedRequest();
        assertEquals("POST", forwarded.method());
        assertEquals(target, forwarded.target());
        assertEquals(body, forwarded.body());
        assertTrue(forwarded.userIds().isEmpty());
        assertTrue(forwarded.authorization().isEmpty());
        assertEquals(1, UPSTREAM_REQUESTS.get());
    }

    @Test
    void loginExceptionDoesNotAllowOtherMethodsOrNearbyPaths() throws Exception {
        for (String[] candidate : new String[][] {
                {"GET", "/api/base/v1/auth/wechat-login"},
                {"POST", "/api/base/v1/auth/wechat-login/"},
                {"POST", "/api/base/v1/auth/wechat-login-extra"}}) {
            HttpResponse<String> response = send(request(candidate[1])
                    .method(candidate[0], HttpRequest.BodyPublishers.noBody()).build());
            assertProblem(response, 401);
        }
        assertEquals(0, UPSTREAM_REQUESTS.get());
    }

    @Test
    void expiredBearerTokenReturnsProblemWithoutCallingUpstream() throws Exception {
        String path = "/api/ai/v1/analysis";
        Instant now = Instant.now();
        String expiredToken = token(now.minusSeconds(300), now.minusSeconds(120));

        HttpResponse<String> response = send(request(path)
                .header("Authorization", "Bearer " + expiredToken).GET().build());

        assertProblem(response, 401);
        assertEquals(0, UPSTREAM_REQUESTS.get());
    }

    @Test
    void missingSigningConfigurationReturnsServiceUnavailableWithoutCallingUpstream() throws Exception {
        String path = "/api/base/v1/profile";
        String originalSecret = authProperties.getSecret();
        try {
            authProperties.setSecret(null);

            HttpResponse<String> response = send(request(path)
                    .header("Authorization", "Bearer " + validToken()).GET().build());

            assertProblem(response, 503);
            assertEquals(0, UPSTREAM_REQUESTS.get());
        } finally {
            authProperties.setSecret(originalSecret);
        }
    }

    @Test
    void internalServicePathsAreNotExposedEvenWithValidToken() throws Exception {
        HttpResponse<String> response = send(request("/internal/image/v1/images/private/metadata")
                .header("Authorization", "Bearer " + validToken()).GET().build());

        assertEquals(404, response.statusCode());
        assertEquals(0, UPSTREAM_REQUESTS.get());
    }

    @Test
    void swaggerOriginCanPreflightAuthorizationAndIdempotencyHeadersWithoutToken() throws Exception {
        String origin = "http://localhost:18081";

        HttpResponse<String> response = send(request("/api/history/v1/history")
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,x-idempotency-key,content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build());

        assertEquals(200, response.statusCode());
        assertEquals(origin, response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        List<String> allowedHeaders = commaSeparatedHeader(response, "Access-Control-Allow-Headers");
        assertTrue(allowedHeaders.containsAll(List.of("authorization", "x-idempotency-key", "content-type")));
        assertTrue(commaSeparatedHeader(response, "Access-Control-Allow-Methods").contains("post"));
        assertEquals(0, UPSTREAM_REQUESTS.get());
    }

    private void assertProblem(HttpResponse<String> response, int status) throws Exception {
        assertEquals(status, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("")
                .startsWith("application/problem+json"));
        if (status == 401) {
            assertTrue(response.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Bearer"));
        }
        var problem = objectMapper.readTree(response.body());
        assertEquals(status, problem.path("status").asInt());
        assertFalse(problem.path("type").asText().isBlank());
        assertFalse(problem.path("title").asText().isBlank());
        assertFalse(problem.path("detail").asText().isBlank());
    }

    private HttpRequest.Builder request(String target) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target))
                .timeout(Duration.ofSeconds(10));
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static ForwardedRequest takeForwardedRequest() throws InterruptedException {
        ForwardedRequest request = FORWARDED.poll(5, TimeUnit.SECONDS);
        assertNotNull(request, "The upstream should receive a forwarded HTTP request");
        return request;
    }

    private static List<String> commaSeparatedHeader(HttpResponse<?> response, String name) {
        return response.headers().allValues(name).stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .toList();
    }

    private static String validToken() throws Exception {
        Instant now = Instant.now();
        return token(now.minusSeconds(5), now.plusSeconds(600));
    }

    private static String token(Instant issuedAt, Instant expiresAt) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(USER_ID)
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", GatewayApplicationTests::recordRequest);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start the local HTTP upstream", exception);
        }
    }

    private static void recordRequest(HttpExchange exchange) throws IOException {
        try (exchange) {
            UPSTREAM_REQUESTS.incrementAndGet();
            FORWARDED.add(new ForwardedRequest(exchange.getRequestMethod(),
                    exchange.getRequestURI().toASCIIString(),
                    List.copyOf(exchange.getRequestHeaders().getOrDefault("X-User-Id", List.of())),
                    List.copyOf(exchange.getRequestHeaders().getOrDefault("Authorization", List.of())),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = "local upstream".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        }
    }

    private record ForwardedRequest(String method, String target, List<String> userIds,
                                    List<String> authorization, String body) {}
}
