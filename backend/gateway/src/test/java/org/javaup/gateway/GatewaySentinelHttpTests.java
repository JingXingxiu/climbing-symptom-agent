package org.javaup.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
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
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.javaup.gateway.config.GatewaySentinelConfiguration;
import org.javaup.gateway.sentinel.PostOnlySentinelGatewayFilter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/** Real HTTP requests through Boot 4 / Gateway 5 and the official Sentinel 1.8.9 adapter. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:classpath:/gateway-test.properties",
        "gateway.sentinel.enabled=true",
        "gateway.sentinel.agent-advance-qps=1",
        "gateway.sentinel.image-upload-qps=1",
        "gateway.sentinel.dashboard="
})
@Execution(ExecutionMode.SAME_THREAD)
class GatewaySentinelHttpTests {

    private static final String SECRET = "sentinel-http-only-signing-secret-at-least-32-bytes";
    private static final String USER_ID = "b81cb813-fc17-4c26-9cce-96884207020c";
    private static final String ADVANCE = "/api/ai/v1/agent-sessions/session-one/advance";
    private static final String UPLOAD = "/api/image/v1/pictures";
    private static final BlockingQueue<List<String>> FORWARDED_IDENTITIES = new LinkedBlockingQueue<>();
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
    private PostOnlySentinelGatewayFilter sentinelGatewayFilter;

    @DynamicPropertySource
    static void configureGateway(DynamicPropertyRegistry registry) {
        registry.add("gateway.auth.secret", () -> SECRET);
        String[] services = {"ai", "image", "history"};
        for (int index = 0; index < services.length; index++) {
            String service = services[index];
            String route = "spring.cloud.gateway.server.webflux.routes[" + index + "].";
            registry.add(route + "id", () -> service + "-service");
            registry.add(route + "uri", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
            registry.add(route + "predicates[0]", () -> "Path=/api/" + service + "/**");
        }
    }

    @BeforeEach
    void startWithFreshSentinelWindow() throws Exception {
        // Let the real Sentinel one-second window expire; do not replace its clock or algorithm.
        Thread.sleep(1100);
        FORWARDED_IDENTITIES.clear();
        UPSTREAM_REQUESTS.set(0);
    }

    @AfterAll
    static void stopUpstream() {
        CLIENT.close();
        UPSTREAM.stop(0);
        GatewayRuleManager.loadRules(Set.of());
        GatewayApiDefinitionManager.loadApiDefinitions(Set.of());
    }

    @Test
    void localRulesAndAdapterAreWiredAfterJwtAuthentication() {
        assertEquals(-90, sentinelGatewayFilter.getOrder());
        assertEquals(2, GatewayRuleManager.getRules().size());
        assertTrue(GatewayRuleManager.getRules().stream().allMatch(rule ->
                rule.getResourceMode() == SentinelGatewayConstants.RESOURCE_MODE_CUSTOM_API_NAME
                        && rule.getCount() == 1 && rule.getIntervalSec() == 1));
        assertNotNull(GatewayApiDefinitionManager.getApiDefinition(GatewaySentinelConfiguration.AGENT_ADVANCE_API));
        assertNotNull(GatewayApiDefinitionManager.getApiDefinition(GatewaySentinelConfiguration.IMAGE_UPLOAD_API));
    }

    @Test
    void underLimitAdvanceAndUploadUseIndependentGroupsAndForwardTrustedIdentity() throws Exception {
        assertEquals(200, send(post(ADVANCE).header("X-User-Id", "forged-user").build()).statusCode());
        assertEquals(200, send(post(UPLOAD).build()).statusCode());
        assertEquals(List.of(USER_ID), FORWARDED_IDENTITIES.poll(5, TimeUnit.SECONDS));
        assertEquals(List.of(USER_ID), FORWARDED_IDENTITIES.poll(5, TimeUnit.SECONDS));
        assertEquals(2, UPSTREAM_REQUESTS.get());
    }

    @Test
    void advanceBurstSharesOneGroupAcrossSessionIdsAndReturnsRfc9457() throws Exception {
        assertBurstLimited(IntStream.range(0, 12)
                .mapToObj(index -> "/api/ai/v1/agent-sessions/session-" + index + "/advance")
                .toList());
    }

    @Test
    void uploadBurstReturns429WithoutCallingUpstreamForBlockedRequests() throws Exception {
        assertBurstLimited(java.util.Collections.nCopies(12, UPLOAD));
    }

    @Test
    void nonTargetPathsAndMethodsDoNotSpendEitherApiGroupBudget() throws Exception {
        for (String path : List.of(
                "/api/ai/v1/agent-sessions/session-one/responses",
                "/api/ai/v1/agent-sessions",
                "/api/ai/v1/agent-sessions/session-one/advance/extra",
                "/api/image/v1/pictures/metadata",
                "/api/history/v1/history")) {
            for (int index = 0; index < 3; index++) {
                assertEquals(200, send(post(path).build()).statusCode());
            }
        }
        for (String path : List.of(ADVANCE, UPLOAD)) {
            for (int index = 0; index < 3; index++) {
                assertEquals(200, send(authorized(path).GET().build()).statusCode());
            }
            assertEquals(200, send(post(path).build()).statusCode());
        }
    }

    @Test
    void rateLimitedGroupStillRequiresValidJwtRegardlessOfForgedUserHeader() throws Exception {
        assertBurstLimited(java.util.Collections.nCopies(12, ADVANCE));
        int forwardedBeforeUnauthorized = UPSTREAM_REQUESTS.get();
        for (String authorization : List.of("", "Bearer invalid-token")) {
            HttpRequest.Builder request = request(ADVANCE).header("X-User-Id", USER_ID);
            if (!authorization.isEmpty()) {
                request.header("Authorization", authorization);
            }
            HttpResponse<String> response = send(request.POST(HttpRequest.BodyPublishers.noBody()).build());
            assertEquals(401, response.statusCode());
            assertTrue(response.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Bearer"));
            assertFalse(objectMapper.readTree(response.body()).path("code").asText().equals("GATEWAY_RATE_LIMITED"));
        }
        assertEquals(forwardedBeforeUnauthorized, UPSTREAM_REQUESTS.get());
    }

    @Test
    void unauthorizedRequestsDoNotConsumeTheProtectedBudget() throws Exception {
        for (int index = 0; index < 4; index++) {
            assertEquals(401, send(request(ADVANCE).header("X-User-Id", USER_ID)
                    .POST(HttpRequest.BodyPublishers.noBody()).build()).statusCode());
        }
        assertEquals(200, send(post(ADVANCE).build()).statusCode());
        assertEquals(1, UPSTREAM_REQUESTS.get());
    }

    @Test
    void upstreamBusiness429AndPayloadArePreserved() throws Exception {
        HttpResponse<String> response = send(post(ADVANCE).header("X-Test-Business-Quota", "true").build());
        assertEquals(429, response.statusCode());
        assertEquals("AI_DAILY_QUOTA_EXCEEDED", objectMapper.readTree(response.body()).path("code").asText());
        assertTrue(response.headers().firstValue("Retry-After").isEmpty());
        assertEquals(1, UPSTREAM_REQUESTS.get());
    }

    @Test
    void internalEndpointsRemainUnrouted() throws Exception {
        HttpResponse<String> response = send(post("/internal/ai/v1/agent-sessions/session-one/advance").build());
        assertEquals(404, response.statusCode());
        assertEquals(0, UPSTREAM_REQUESTS.get());
    }

    private void assertBurstLimited(List<String> paths) throws Exception {
        List<CompletableFuture<HttpResponse<String>>> pending = paths.stream().map(path -> {
            try {
                return CLIENT.sendAsync(post(path).header("X-User-Id", UUID.randomUUID().toString()).build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }).toList();
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        List<HttpResponse<String>> responses = pending.stream().map(CompletableFuture::join).toList();
        long passed = responses.stream().filter(response -> response.statusCode() == 200).count();
        long blocked = responses.stream().filter(response -> response.statusCode() == 429).count();
        assertTrue(passed >= 1, "A fresh Sentinel window must allow a request");
        assertTrue(blocked >= 1, "A burst above 1 QPS must be rejected by Sentinel");
        assertEquals(paths.size(), passed + blocked);
        assertEquals(passed, UPSTREAM_REQUESTS.get());
        for (HttpResponse<String> response : responses) {
            if (response.statusCode() == 429) {
                assertTrue(response.headers().firstValue("Content-Type").orElse("")
                        .startsWith("application/problem+json"));
                assertEquals("1", response.headers().firstValue("Retry-After").orElseThrow());
                var problem = objectMapper.readTree(response.body());
                assertEquals(429, problem.path("status").asInt());
                assertEquals("GATEWAY_RATE_LIMITED", problem.path("code").asText());
                assertEquals("/problems/gateway-rate-limited", problem.path("type").asText());
                assertEquals("Too Many Requests", problem.path("title").asText());
                assertFalse(problem.path("detail").asText().isBlank());
                assertFalse(problem.path("traceId").asText().isBlank());
                assertNotNull(UUID.fromString(problem.path("traceId").asText()));
                assertTrue(paths.contains(problem.path("instance").asText()));
            }
        }
    }

    private HttpRequest.Builder post(String path) throws Exception {
        return authorized(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
    }

    private HttpRequest.Builder authorized(String path) throws Exception {
        return request(path).header("Authorization", "Bearer " + token());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String token() throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(USER_ID)
                .issuer("climbing-base-service").audience("climbing-api")
                .issueTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(now.plusSeconds(600)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", GatewaySentinelHttpTests::recordRequest);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start local Sentinel test upstream", exception);
        }
    }

    private static void recordRequest(HttpExchange exchange) throws IOException {
        try (exchange) {
            UPSTREAM_REQUESTS.incrementAndGet();
            FORWARDED_IDENTITIES.add(List.copyOf(
                    exchange.getRequestHeaders().getOrDefault("X-User-Id", List.of())));
            exchange.getRequestBody().readAllBytes();
            boolean businessQuota = exchange.getRequestHeaders().containsKey("X-Test-Business-Quota");
            byte[] body = (businessQuota ? "{\"code\":\"AI_DAILY_QUOTA_EXCEEDED\"}" : "local upstream")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",
                    businessQuota ? "application/problem+json" : "text/plain");
            exchange.sendResponseHeaders(businessQuota ? 429 : 200, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
