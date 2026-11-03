package org.javaup.ai.search;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

class SearchToolTest {
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void disabledOrMissingKeyDoesNotPretendToSearch() {
        SearchProperties properties = new SearchProperties();
        SearchTool tool = new SearchTool(properties, mapper);
        assertFalse(tool.search("攀岩手指").available());
        properties.setEnabled(true);
        assertFalse(tool.search("攀岩手指").available());
        assertTrue(tool.search("攀岩手指").references().isEmpty());
    }

    @Test
    void queryDoesNotIncludeIdentityPrivateNotesOrPictureUrl() {
        JsonNode input =
                mapper.readTree(
                        """
                {"assessment":{"initialInput":{"symptomTypes":["NUMBNESS","WEAKNESS"],
                "userNote":"张三身份证及私密症状描述","userId":"secret-user"}},
                "pictures":[{"originalUrl":"https://private.invalid/photo?token=secret"}]}
                """);
        String query = new SearchTool(new SearchProperties(), mapper).queryFor(input);
        assertTrue(query.contains("麻木"));
        assertTrue(query.contains("无力"));
        assertFalse(query.contains("张三"));
        assertFalse(query.contains("secret"));
        assertFalse(query.contains("https"));
    }

    @Test
    void standaloneApiParsesOnlyHttpSourcesAndLimitsResultCount() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JsonNode> submitted = new AtomicReference<>();
        server.createContext(
                "/search",
                exchange -> {
                    submitted.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
                    byte[] bytes =
                            """
                    {"search_result":[{"title":"不合法来源","link":"file:///etc/passwd","content":"skip"},
                    {"title":"公开医学资料","link":"https://example.org/reference","content":"内容摘要"},
                    {"title":"第二条","link":"https://example.org/second","content":"more"}]}
                    """
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            SearchProperties properties = new SearchProperties();
            properties.setEnabled(true);
            properties.setApiKey("local-test-key");
            properties.setMaxResults(1);
            properties.setEndpoint(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search"));
            SearchTool.SearchReport report = new SearchTool(properties, mapper).search("攀岩 手部 麻木");
            assertTrue(report.available());
            assertEquals(1, report.references().size());
            assertEquals("https://example.org/reference", report.references().getFirst().url());
            assertEquals("攀岩 手部 麻木", submitted.get().path("search_query").asText());
            assertEquals("search_std", submitted.get().path("search_engine").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void remoteErrorDoesNotLeakProviderBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/search",
                exchange -> {
                    exchange.sendResponseHeaders(401, 0);
                    exchange.getResponseBody()
                            .write("private-provider-error".getBytes(StandardCharsets.UTF_8));
                    exchange.close();
                });
        server.start();
        try {
            SearchProperties properties = new SearchProperties();
            properties.setEnabled(true);
            properties.setApiKey("test");
            properties.setEndpoint(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search"));
            SearchTool.SearchReport report = new SearchTool(properties, mapper).search("攀岩 手部");
            assertFalse(report.available());
            assertFalse(report.note().contains("private-provider-error"));
            assertTrue(report.references().isEmpty());
        } finally {
            server.stop(0);
        }
    }
}
