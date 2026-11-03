package org.javaup.ai.model;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;

import org.javaup.ai.api.dto.AgentEnums.AgentAnswerType;
import org.javaup.ai.api.dto.AgentEnums.AgentBehavior;
import org.javaup.ai.api.dto.AgentQuestion;
import org.javaup.ai.api.problem.ApiException;
import org.javaup.ai.observability.AgentTelemetry;
import org.javaup.ai.rag.KnowledgeReference;
import org.javaup.ai.rag.KnowledgeRetrievalService;
import org.javaup.ai.rag.KnowledgeSearchReport;
import org.javaup.ai.rag.KnowledgeSearchStatus;
import org.javaup.ai.search.SearchProperties;
import org.javaup.ai.search.SearchTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 真实SpringAI序列化路径，仅连接本机假供应商，不调用计费接口。 */
class SpringAICompatibleModelHttpTest {
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final ObservationRegistry observationRegistry = ObservationRegistry.create();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<ObservedStage> observed = new ArrayList<>();

    @BeforeEach
    void collectOnlySafeObservationMetadata() {
        observationRegistry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                var status = context.getLowCardinalityKeyValue("status");
                observed.add(new ObservedStage(
                        context.getName(),
                        status == null ? null : status.getValue(),
                        context.getError(),
                        context.getAllKeyValues().toString()));
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"qwen", "zhipu"})
    void oneModelCallCarriesImageBytesAndDropsInventedSources(String provider) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JsonNode> sent = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        server.createContext(
                "/picture",
                exchange -> {
                    exchange.getResponseHeaders().set("Content-Type", "image/png");
                    exchange.sendResponseHeaders(200, 3);
                    exchange.getResponseBody().write(new byte[] {1, 2, 3});
                    exchange.close();
                });
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    calls.incrementAndGet();
                    sent.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
                    AgentDecision decision =
                            new AgentDecision(
                                    AgentBehavior.NARROW_DOWN,
                                    "请补充诱发动作",
                                    new AgentQuestion(
                                            null,
                                            "trigger.context",
                                            "哪个动作最明显？",
                                            null,
                                            AgentAnswerType.TEXT,
                                            List.of(),
                                            true),
                                    mapper.createObjectNode(),
                                    List.of(),
                                    null,
                                    List.of(
                                            new AgentDecision.Reference(
                                                    "模型捏造链接",
                                                    "https://invented.invalid",
                                                    "not verified")));
                    String content = mapper.writeValueAsString(decision);
                    byte[] response =
                            mapper.writeValueAsBytes(
                                    Map.of(
                                            "id",
                                            "local-call-1",
                                            "object",
                                            "chat.completion",
                                            "created",
                                            1,
                                            "model",
                                            provider + "-test",
                                            "choices",
                                            List.of(
                                                    Map.of(
                                                            "index",
                                                            0,
                                                            "message",
                                                            Map.of(
                                                                    "role",
                                                                    "assistant",
                                                                    "content",
                                                                    content),
                                                            "finish_reason",
                                                            "stop")),
                                            "usage",
                                            Map.of(
                                                    "prompt_tokens",
                                                    100,
                                                    "completion_tokens",
                                                    30,
                                                    "total_tokens",
                                                    130)));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        try {
            int port = server.getAddress().getPort();
            AgentModelProperties properties = new AgentModelProperties();
            var profile =
                    new AgentModelProperties.Profile(
                            provider,
                            provider + "-test",
                            "http://127.0.0.1:" + port + "/v1",
                            "local-key");
            SpringAICompatibleModel model =
                    model(properties, new SearchTool(new SearchProperties(), mapper));
            var request =
                    new AgentModelRequest(
                            UUID.randomUUID(),
                            provider,
                            provider + "-test",
                            "zh-CN",
                            mapper.createObjectNode(),
                            mapper.createObjectNode(),
                            List.of(),
                            List.of(
                                    new AgentModelRequest.Picture(
                                            UUID.randomUUID(),
                                            URI.create("http://127.0.0.1:" + port + "/picture"),
                                            "image/png")));
            AgentModelResult result = model.analyze(request, profile);
            assertEquals(1, calls.get());
            assertEquals("local-call-1", result.providerRequestId());
            assertEquals(130, result.totalTokens());
            assertNotNull(result.decision().question().questionId());
            assertTrue(result.decision().references().isEmpty());
            assertTrue(sent.get().toString().contains("data:image/png;base64,AQID"));
            assertFalse(sent.get().toString().contains("/picture"));
            assertEquals(
                    List.of("agent.knowledge_retrieval", "agent.web_search", "agent.image_download",
                            "agent.model_request", "agent.output_validation"),
                    observed.stream().map(ObservedStage::name).filter(name -> name.startsWith("agent.")).toList());
            assertEquals("unavailable", stage("agent.web_search").status());
            assertEquals("disabled", stage("agent.knowledge_retrieval").status());
            assertNull(stage("agent.model_request").failure());
            assertTrue(observed.stream().anyMatch(stage -> stage.name().equals("gen_ai.client.operation")),
                    "The manually constructed Spring AI client must use the injected ObservationRegistry");
            assertTrue(observed.stream().noneMatch(stage -> stage.tags().contains("data:image")
                    || stage.tags().contains("/picture") || stage.tags().contains("local-key")));
            if ("zhipu".equals(provider)) {
                assertEquals("disabled", sent.get().path("thinking").path("type").asText());
                assertFalse(sent.get().has("response_format"));
            } else {
                assertFalse(sent.get().path("enable_thinking").asBoolean(true));
            }
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///private/reference.md", "https://reference.invalid/article"})
    void knowledgeSourcesKeepRealIdsAndVersionWhileHidingInternalUris(String sourceUri) throws Exception {
        UUID documentId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        KnowledgeRetrievalService knowledge = mock(KnowledgeRetrievalService.class);
        when(knowledge.retrieve(any())).thenReturn(new KnowledgeSearchReport(
                KnowledgeSearchStatus.FOUND, "Retrieved reference", List.of(new KnowledgeReference(
                        documentId, chunkId, "Actual reference", sourceUri, "2026-11",
                        "A source passage for symptom clarification.", 0.91))));
        AtomicReference<JsonNode> sent = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            sent.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            AgentDecision decision = new AgentDecision(
                    AgentBehavior.NARROW_DOWN, "请补充动作",
                    new AgentQuestion(null, "trigger.context", "什么动作明显？", null,
                            AgentAnswerType.TEXT, List.of(), true),
                    mapper.createObjectNode(), List.of(), null,
                    List.of(new AgentDecision.Reference("Invented source", "https://invented.invalid", "invented")));
            byte[] response = mapper.writeValueAsBytes(Map.of(
                    "id", "knowledge-call", "object", "chat.completion", "created", 1, "model", "test",
                    "choices", List.of(Map.of("index", 0, "message",
                            Map.of("role", "assistant", "content", mapper.writeValueAsString(decision)),
                            "finish_reason", "stop")),
                    "usage", Map.of("prompt_tokens", 12, "completion_tokens", 5, "total_tokens", 17)));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            SpringAICompatibleModel model = new SpringAICompatibleModel(
                    new AgentModelProperties(), mapper, new SearchTool(new SearchProperties(), mapper),
                    new AgentTelemetry(observationRegistry, meters), observationRegistry, knowledge);
            AgentModelProperties.Profile profile = new AgentModelProperties.Profile(
                    "qwen", "test", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test");
            AgentModelRequest request = new AgentModelRequest(
                    UUID.randomUUID(), "qwen", "test", "zh-CN", mapper.createObjectNode(),
                    mapper.createObjectNode(), List.of(), List.of());

            AgentModelResult result = model.analyze(request, profile);

            assertEquals(1, result.decision().references().size());
            AgentDecision.Reference source = result.decision().references().getFirst();
            assertEquals(documentId, source.documentId());
            assertEquals(chunkId, source.chunkId());
            assertEquals("KNOWLEDGE", source.sourceType());
            assertEquals("2026-11", source.contentVersion());
            assertEquals(sourceUri.startsWith("https:") ? sourceUri : null, source.url());
            assertFalse(sent.get().toString().contains("file:///private"));
            assertEquals("found", stage("agent.knowledge_retrieval").status());
            verify(knowledge).retrieve(request.inputSnapshot());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void providerHttpFailureDoesNotTriggerHiddenRetries() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    calls.incrementAndGet();
                    byte[] response =
                            "{\"error\":{\"message\":\"temporary failure\",\"type\":\"server_error\"}}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(500, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        try {
            var profile =
                    new AgentModelProperties.Profile(
                            "qwen",
                            "test",
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                            "test");
            SpringAICompatibleModel model =
                    model(new AgentModelProperties(), new SearchTool(new SearchProperties(), mapper));
            var request =
                    new AgentModelRequest(
                            UUID.randomUUID(),
                            "qwen",
                            "test",
                            "zh-CN",
                            mapper.createObjectNode(),
                            mapper.createObjectNode(),
                            List.of(),
                            List.of());
            assertEquals(
                    "MODEL_CALL_FAILED",
                    assertThrows(ApiException.class, () -> model.analyze(request, profile)).code());
            assertEquals(1, calls.get());
            assertNotNull(stage("agent.model_request").failure());
            assertTrue(observed.stream().noneMatch(stage -> stage.name().equals("agent.output_validation")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void invalidStructuredOutputFailsValidationAfterSuccessfulModelRequest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = mapper.writeValueAsBytes(Map.of(
                    "id", "invalid-output-call",
                    "object", "chat.completion",
                    "created", 1,
                    "model", "test",
                    "choices", List.of(Map.of(
                            "index", 0,
                            "message", Map.of("role", "assistant", "content", "not a workflow decision"),
                            "finish_reason", "stop")),
                    "usage", Map.of("prompt_tokens", 12, "completion_tokens", 5, "total_tokens", 17)));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            AgentModelProperties.Profile profile = new AgentModelProperties.Profile(
                    "qwen", "test", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test");
            AgentModelRequest request = new AgentModelRequest(
                    UUID.randomUUID(), "qwen", "test", "zh-CN",
                    mapper.createObjectNode().put("privateNote", "private symptom detail"),
                    mapper.createObjectNode(), List.of(), List.of());
            ApiException failure = assertThrows(ApiException.class, () ->
                    model(new AgentModelProperties(), new SearchTool(new SearchProperties(), mapper))
                            .analyze(request, profile));

            assertEquals("INVALID_MODEL_OUTPUT", failure.code());
            assertNull(stage("agent.model_request").failure());
            assertNotNull(stage("agent.output_validation").failure());
            assertTrue(observed.stream().noneMatch(stage -> stage.tags().contains("private symptom detail")));
            assertFalse(meters.getMeters().isEmpty(), "Billed usage is recorded even when output is invalid");
        } finally {
            server.stop(0);
        }
    }

    private SpringAICompatibleModel model(AgentModelProperties properties, SearchTool search) {
        return new SpringAICompatibleModel(
                properties, mapper, search, new AgentTelemetry(observationRegistry, meters), observationRegistry);
    }

    private ObservedStage stage(String name) {
        return observed.stream().filter(stage -> stage.name().equals(name)).findFirst().orElseThrow();
    }

    private record ObservedStage(String name, String status, Throwable failure, String tags) {}
}
