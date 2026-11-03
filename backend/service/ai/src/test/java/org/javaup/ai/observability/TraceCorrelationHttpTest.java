package org.javaup.ai.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.javaup.ai.model.AgentModelProperties;
import org.javaup.ai.model.AgentModelRequest;
import org.javaup.ai.model.SpringAICompatibleModel;
import org.javaup.ai.search.SearchProperties;
import org.javaup.ai.search.SearchTool;
import org.javaup.ai.api.problem.ApiException;
import org.javaup.ai.api.problem.ApiProblemAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** 真实本机 HTTP 验证 W3C 链路继承，不依赖数据库/模型/Collector。 */
@SpringBootTest(
        classes = TraceCorrelationHttpTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.location=optional:classpath:/observability-http-test.properties",
                "spring.config.import=",
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.config.import-check.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration,org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration,org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration,org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration,org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration,org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration",
                "management.server.port=-1",
                "management.tracing.export.otlp.enabled=false",
                "management.logging.export.otlp.enabled=false",
                "management.otlp.metrics.export.enabled=false",
                "management.tracing.sampling.probability=1.0",
                "management.tracing.propagation.consume=W3C",
                "management.tracing.propagation.produce=W3C"
        })
class TraceCorrelationHttpTest {

    private static final String TRACE = "0123456789abcdef0123456789abcdef";

    @LocalServerPort
    private int port;

    @Autowired
    private ObservationRegistry observations;

    @Autowired
    private AgentTelemetry telemetry;

    @Autowired
    private CapturedSpans captured;

    @Test
    void w3cTraceIsSharedByHeaderExecutionAndProblem() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> ok = client.send(request("/probe"), HttpResponse.BodyHandlers.ofString());
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.headers().firstValue("X-Trace-Id")).contains(TRACE);
        assertThat(ok.body()).contains(TRACE).doesNotContain("attacker-controlled");

        HttpResponse<String> failed = client.send(request("/probe/fail"), HttpResponse.BodyHandlers.ofString());
        assertThat(failed.statusCode()).isEqualTo(409);
        assertThat(failed.headers().firstValue("X-Trace-Id")).contains(TRACE);
        assertThat(failed.body()).contains("\"traceId\":\"" + TRACE + "\"");
    }

    @Test
    void nativeSdkErrorsAreSanitizedBeforeActualTracingExport() throws Exception {
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/chat/completions", exchange -> {
            byte[] body = "{\"error\":{\"message\":\"SECRET-user-symptom\",\"type\":\"server_error\"}}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        provider.start();
        captured.spans().clear();

        try {
            JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
            SpringAICompatibleModel model = new SpringAICompatibleModel(
                    new AgentModelProperties(), mapper, new SearchTool(new SearchProperties(), mapper),
                    telemetry, observations);
            AgentModelRequest request = new AgentModelRequest(
                    UUID.randomUUID(), "qwen", "privacy-test", "zh-CN",
                    mapper.createObjectNode(), mapper.createObjectNode(), List.of(), List.of());
            AgentModelProperties.Profile profile = new AgentModelProperties.Profile(
                    "qwen", "privacy-test", "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1", "test");

            ApiException failure = assertThrows(ApiException.class, () -> model.analyze(request, profile));

            assertThat(failure.code()).isEqualTo("MODEL_CALL_FAILED");
            assertThat(failure.getMessage()).doesNotContain("SECRET");
            // 读取实际 OTel exporter 接收的 Span，而不是只断言 Observation.Context。
            List<SpanData> nativeSpans = captured.spans().stream()
                    .filter(span -> span.getName().contains("privacy-test"))
                    .toList();
            assertThat(nativeSpans).isNotEmpty();
            assertThat(nativeSpans.stream().flatMap(span -> span.getEvents().stream())
                    .filter(event -> event.getName().equals("exception"))
                    .map(event -> event.getAttributes().toString()).toList())
                    .isNotEmpty()
                    .allSatisfy(event -> assertThat(event)
                            .contains("Model request failed")
                            .doesNotContain("SECRET", "OpenAI", "Caused by"));
            assertThat(captured.spans().stream().map(Object::toString).toList())
                    .allSatisfy(span -> assertThat(span).doesNotContain("SECRET"));
        } finally {
            provider.stop(0);
        }
    }

    @Test
    void disabledTracingDoesNotBecomeARequiredBean() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                SafeModelErrorObservationHandler.class)) {
            assertThat(context.getBean(SafeModelErrorObservationHandler.class).getTracer()).isSameAs(Tracer.NOOP);
        }
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("traceparent", "00-" + TRACE + "-0123456789abcdef-01")
                .header("X-Trace-Id", "attacker-controlled")
                .build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({AgentTelemetry.class, TraceCorrelationFilter.class, SafeModelErrorObservationHandler.class,
            ApiProblemAdvice.class, ProbeController.class})
    static class TestApplication {

        @Bean
        CapturedSpans capturedSpans() {
            return new CapturedSpans(new CopyOnWriteArrayList<>());
        }

        @Bean
        SpanProcessor captureSpanProcessor(CapturedSpans captured) {
            return SimpleSpanProcessor.create(new SpanExporter() {
                @Override
                public CompletableResultCode export(Collection<SpanData> spans) {
                    captured.spans().addAll(spans);
                    return CompletableResultCode.ofSuccess();
                }

                @Override
                public CompletableResultCode flush() {
                    return CompletableResultCode.ofSuccess();
                }

                @Override
                public CompletableResultCode shutdown() {
                    return CompletableResultCode.ofSuccess();
                }
            });
        }
    }

    record CapturedSpans(List<SpanData> spans) {}

    @RestController
    static class ProbeController {

        @Autowired
        private AgentTelemetry telemetry;

        @GetMapping("/probe")
        Map<String, String> probe() {
            return telemetry.inSession(UUID.randomUUID(), () ->
                    Map.of("traceId", telemetry.currentTraceId()));
        }

        @GetMapping("/probe/fail")
        void fail() {
            throw new ApiException(HttpStatus.CONFLICT, "PROBE_FAILED", "Expected probe failure");
        }
    }
}
