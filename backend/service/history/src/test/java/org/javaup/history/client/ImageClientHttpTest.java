package org.javaup.history.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import feign.FeignException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.javaup.history.HistoryApplication;
import org.javaup.history.api.dto.HistoryEnums.VisualSourceType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/** Exercises the actual Spring Feign encoder/decoder against a loopback-only HTTP stub. */
@SpringBootTest(classes = HistoryApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.config.name=history-offline",
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.config.import-check.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/history_offline",
                "spring.datasource.username=offline",
                "spring.datasource.password=offline",
                "spring.datasource.hikari.initialization-fail-timeout=-1",
                "spring.datasource.hikari.minimum-idle=0",
                "spring.sql.init.mode=never",
                "mybatis-plus.type-handlers-package=org.javaup.history.persistence.typehandler",
                "spring.cloud.openfeign.client.config.image-service.connectTimeout=2000",
                "spring.cloud.openfeign.client.config.image-service.readTimeout=2000"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ImageClientHttpTest {

    private static final Queue<StubResponse> RESPONSES = new ConcurrentLinkedQueue<>();
    private static final Queue<ReceivedRequest> REQUESTS = new ConcurrentLinkedQueue<>();
    private static final HttpServer SERVER = startServer();

    @Autowired
    private ImageClient imageClient;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void configureLocalImageEndpoint(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.image-service.url",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @BeforeEach
    void resetStub() {
        RESPONSES.clear();
        REQUESTS.clear();
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Test
    void sendsPictureHeaderAndPathAndDecodesFullResponseIntoConsumerDto() {
        UUID userId = UUID.randomUUID();
        UUID pictureId = UUID.randomUUID();
        RESPONSES.add(new StubResponse(200, "application/json", """
                {
                  "pictureId": "%s",
                  "ownerUserId": "%s",
                  "sourceType": "USER_UPLOAD",
                  "uploadStatus": "AVAILABLE",
                  "processingStatus": "PENDING",
                  "originalFilename": "hand.jpg",
                  "originalUrl": "https://example.invalid/hand.jpg",
                  "thumbnailUrl": null,
                  "byteSize": 1024,
                  "viewState": null
                }
                """.formatted(pictureId, userId)));

        ImageClient.PictureLookupResponse response = imageClient.getPicture(userId, pictureId);

        assertEquals(pictureId, response.pictureId());
        assertEquals(userId, response.ownerUserId());
        assertEquals(VisualSourceType.USER_UPLOAD, response.sourceType());
        assertEquals("AVAILABLE", response.uploadStatus());
        ReceivedRequest request = REQUESTS.poll();
        assertNotNull(request);
        assertEquals("GET", request.method());
        assertEquals("/api/image/v1/pictures/" + pictureId, request.path());
        assertEquals(userId.toString(), request.userId());
        assertNull(request.query());
        assertEquals(0, RESPONSES.size());
    }

    @Test
    void sendsAssessmentQueryAndDecodesMinimalAnnotationIdentity() {
        UUID userId = UUID.randomUUID();
        UUID pictureId = UUID.randomUUID();
        UUID assessmentId = UUID.randomUUID();
        UUID annotationSetId = UUID.randomUUID();
        RESPONSES.add(new StubResponse(200, "application/json", """
                {
                  "annotationSetId": "%s",
                  "pictureId": "%s",
                  "coordinateSpace": "NORMALIZED_0_1",
                  "sourceWidth": 1200,
                  "sourceHeight": 800,
                  "shapes": [],
                  "note": "右手中指不适",
                  "version": 2,
                  "updatedAt": "2026-10-23T00:00:00Z"
                }
                """.formatted(annotationSetId, pictureId)));

        ImageClient.AnnotationSetLookupResponse response = imageClient.getAnnotationSet(
                userId, pictureId, assessmentId);

        assertEquals(annotationSetId, response.annotationSetId());
        assertEquals(pictureId, response.pictureId());
        ReceivedRequest request = REQUESTS.poll();
        assertNotNull(request);
        assertEquals("GET", request.method());
        assertEquals("/api/image/v1/pictures/" + pictureId + "/annotation-set", request.path());
        assertEquals("assessmentId=" + assessmentId, request.query());
        assertEquals(userId.toString(), request.userId());
    }

    @Test
    void sendsOneInternalPostWithAssessmentAndPictureIdsAndDecodesBatch() {
        UUID userId = UUID.randomUUID();
        UUID assessmentId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        UUID annotationSetId = UUID.randomUUID();
        RESPONSES.add(new StubResponse(200, "application/json", """
                [
                  {"pictureId":"%s","sourceType":"USER_UPLOAD","annotationSetId":"%s"},
                  {"pictureId":"%s","sourceType":"STANDARD_REFERENCE","annotationSetId":null}
                ]
                """.formatted(firstId, annotationSetId, secondId)));

        List<ImageClient.ResolvedVisualReference> response = imageClient.resolveVisualReferences(
                userId, new ImageClient.ResolveVisualReferencesRequest(
                        assessmentId, List.of(firstId, secondId)));

        assertEquals(List.of(firstId, secondId), response.stream()
                .map(ImageClient.ResolvedVisualReference::pictureId).toList());
        assertEquals(VisualSourceType.USER_UPLOAD, response.getFirst().sourceType());
        assertEquals(annotationSetId, response.getFirst().annotationSetId());
        assertNull(response.get(1).annotationSetId());
        ReceivedRequest request = REQUESTS.poll();
        assertNotNull(request);
        assertEquals("POST", request.method());
        assertEquals("/internal/image/visual-references/resolve", request.path());
        assertNull(request.query());
        assertEquals(userId.toString(), request.userId());
        assertEquals(true, request.contentType().startsWith("application/json"));
        var body = objectMapper.readTree(request.body());
        assertEquals(assessmentId.toString(), body.path("assessmentId").asText());
        assertEquals(firstId.toString(), body.path("pictureIds").get(0).asText());
        assertEquals(secondId.toString(), body.path("pictureIds").get(1).asText());
        assertEquals(2, body.path("pictureIds").size());
        assertEquals(0, REQUESTS.size());
        assertEquals(0, RESPONSES.size());
    }

    @Test
    void propagatesBatchConflictAsFeignConflict() {
        RESPONSES.add(new StubResponse(409, "application/problem+json", """
                {"status":409,"code":"PICTURE_NOT_AVAILABLE"}
                """));

        FeignException.Conflict exception = assertThrows(FeignException.Conflict.class,
                () -> imageClient.resolveVisualReferences(UUID.randomUUID(),
                        new ImageClient.ResolveVisualReferencesRequest(
                                UUID.randomUUID(), List.of(UUID.randomUUID()))));

        assertEquals(409, exception.status());
        assertEquals(1, REQUESTS.size());
    }

    @Test
    void preservesNotFoundProblemBodyForBusinessCodeInspection() {
        UUID pictureId = UUID.randomUUID();
        RESPONSES.add(new StubResponse(404, "application/problem+json", """
                {
                  "type": "about:blank",
                  "title": "Not Found",
                  "status": 404,
                  "detail": "Annotation set does not exist",
                  "code": "ANNOTATION_SET_NOT_FOUND"
                }
                """));

        FeignException.NotFound exception = assertThrows(FeignException.NotFound.class,
                () -> imageClient.getAnnotationSet(UUID.randomUUID(), pictureId, UUID.randomUUID()));

        assertEquals(404, exception.status());
        assertEquals("ANNOTATION_SET_NOT_FOUND", objectMapper.readTree(exception.contentUTF8())
                .path("code").asText());
        assertEquals(1, REQUESTS.size());
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ImageClientHttpTest::handle);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        REQUESTS.add(new ReceivedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders().getFirst("X-User-Id"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        StubResponse response = RESPONSES.poll();
        if (response == null) {
            response = new StubResponse(500, "application/problem+json", "{\"code\":\"UNEXPECTED_REQUEST\"}");
        }
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        try {
            exchange.getResponseHeaders().set("Content-Type", response.contentType() + "; charset=UTF-8");
            exchange.sendResponseHeaders(response.status(), body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }

    private record StubResponse(int status, String contentType, String body) {}

    private record ReceivedRequest(String method, String path, String query, String userId,
                                   String contentType, String body) {}
}
