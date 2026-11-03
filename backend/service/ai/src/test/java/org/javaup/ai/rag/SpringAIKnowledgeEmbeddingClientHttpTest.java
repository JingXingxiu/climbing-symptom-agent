package org.javaup.ai.rag;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.javaup.ai.observability.AgentTelemetry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 验证真正的 Spring AI SDK 请求，仅连接本机 HTTP stub，不调用付费平台。 */
class SpringAIKnowledgeEmbeddingClientHttpTest {
    @Test
    void configuredCompatibleProviderReceivesOnePublicQueryAndExplicitDimensions() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JsonNode> body = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/v1/embeddings", exchange -> {
            calls.incrementAndGet();
            body.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] response = """
                    {"object":"list","model":"embedding-test",
                     "data":[{"object":"embedding","index":0,"embedding":[1.0,0.0,0.0]}],
                     "usage":{"prompt_tokens":4,"total_tokens":4}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            KnowledgeRetrievalProperties properties = new KnowledgeRetrievalProperties();
            properties.setProvider("local");
            properties.setModel("embedding-test");
            properties.setDimensions(3);
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            properties.setApiKey("local-test-key");
            ObservationRegistry observations = ObservationRegistry.create();
            var client = new SpringAIKnowledgeEmbeddingClient(properties, observations,
                    new AgentTelemetry(observations, new SimpleMeterRegistry()));
            assertArrayEquals(new float[] {1, 0, 0}, client.embed("攀岩 手部 麻木"));
            assertEquals(1, calls.get());
            assertEquals("embedding-test", body.get().path("model").asString());
            assertEquals(3, body.get().path("dimensions").asInt());
            assertTrue(body.get().path("input").toString().contains("攀岩 手部 麻木"));
            assertFalse(body.get().has("user"));
        } finally {
            server.stop(0);
        }
    }
}
