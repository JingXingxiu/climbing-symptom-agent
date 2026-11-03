package org.javaup.ai.model;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.javaup.ai.api.dto.AgentEnums.AgentAnswerType;
import org.javaup.ai.api.dto.AgentEnums.AgentBehavior;
import org.javaup.ai.api.dto.AgentQuestion;
import org.javaup.ai.api.problem.ApiException;
import org.javaup.ai.search.SearchProperties;
import org.javaup.ai.search.SearchTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 真实SpringAI序列化路径，仅连接本机假供应商，不调用计费接口。 */
class SpringAICompatibleModelHttpTest {
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

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
                    new SpringAICompatibleModel(
                            properties, mapper, new SearchTool(new SearchProperties(), mapper));
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
                    new SpringAICompatibleModel(
                            new AgentModelProperties(),
                            mapper,
                            new SearchTool(new SearchProperties(), mapper));
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
        } finally {
            server.stop(0);
        }
    }
}
