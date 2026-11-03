package org.javaup.history.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.javaup.history.HistoryApplication;
import org.javaup.history.api.dto.HistoryEnums.VisualSourceType;
import org.javaup.history.client.ImageClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/**
 * Opt-in, sequential HTTP microbenchmark, not an end-to-end service load test.
 * Uses the production Spring Feign bean and a loopback-only HTTP stub. No Image
 * controller, database, MinIO, Gateway, Nacos or real network impairment is used.
 *
 * Run with -Dvisual.benchmark=true. Override warmup/samples using
 * -Dvisual.benchmark.warmup=5 -Dvisual.benchmark.samples=25.
 */
@SpringBootTest(classes = HistoryApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.config.name=history-offline",
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.config.import-check.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/history_benchmark_offline",
                "spring.datasource.username=offline",
                "spring.datasource.password=offline",
                "spring.datasource.hikari.initialization-fail-timeout=-1",
                "spring.datasource.hikari.minimum-idle=0",
                "spring.sql.init.mode=never",
                "mybatis-plus.type-handlers-package=org.javaup.history.persistence.typehandler",
                "spring.cloud.openfeign.client.config.image-service.connectTimeout=2000",
                "spring.cloud.openfeign.client.config.image-service.readTimeout=2000"
        })
@EnabledIfSystemProperty(named = "visual.benchmark", matches = "true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class VisualReferenceBatchBenchmarkTest {

    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ASSESSMENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final String BATCH_PATH = "/internal/image/visual-references/resolve";
    private static final String PICTURE_PREFIX = "/api/image/v1/pictures/";
    private static final AtomicReference<Fixture> ACTIVE_FIXTURE = new AtomicReference<>();
    private static final AtomicLong REQUESTS = new AtomicLong();
    private static final AtomicLong REQUEST_JSON_BYTES = new AtomicLong();
    private static final AtomicLong RESPONSE_JSON_BYTES = new AtomicLong();
    private static final AtomicLong UNEXPECTED_REQUESTS = new AtomicLong();
    private static final HttpServer SERVER = startServer();

    @Autowired
    private ImageClient imageClient;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void configureLoopbackEndpoint(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.image-service.url",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Test
    void recordsPairedOldAndBatchHttpMeasurements() throws IOException {
        int warmupPairs = Integer.getInteger("visual.benchmark.warmup", 5);
        int samplePairs = Integer.getInteger("visual.benchmark.samples", 25);
        int injectedDelayMs = Integer.getInteger("visual.benchmark.delayMs", 5);
        assertTrue(warmupPairs >= 1 && warmupPairs <= 100, "warmup must be 1..100");
        assertTrue(samplePairs >= 20 && samplePairs <= 500, "samples must be 20..500");
        assertTrue(injectedDelayMs >= 1 && injectedDelayMs <= 100, "delayMs must be 1..100");

        Fixture fixture = new Fixture(objectMapper);
        ACTIVE_FIXTURE.set(fixture);
        List<Sample> samples = new ArrayList<>();
        List<Statistics> statistics = new ArrayList<>();
        List<Comparison> comparisons = new ArrayList<>();

        for (int delayMs : List.of(0, injectedDelayMs)) {
            fixture.delayMs = delayMs;
            for (int pictureCount : List.of(1, 6, 12)) {
                List<UUID> pictureIds = fixture.pictureIds.subList(0, pictureCount);

                for (int warmup = 0; warmup < warmupPairs; warmup++) {
                    if ((warmup & 1) == 0) {
                        runOld(pictureIds);
                        runBatch(pictureIds);
                    } else {
                        runBatch(pictureIds);
                        runOld(pictureIds);
                    }
                }

                List<Sample> oldSamples = new ArrayList<>();
                List<Sample> batchSamples = new ArrayList<>();
                for (int pair = 0; pair < samplePairs; pair++) {
                    if ((pair & 1) == 0) {
                        oldSamples.add(measure(pair, delayMs, pictureIds, "old-sequential-get"));
                        batchSamples.add(measure(pair, delayMs, pictureIds, "batch-post"));
                    } else {
                        batchSamples.add(measure(pair, delayMs, pictureIds, "batch-post"));
                        oldSamples.add(measure(pair, delayMs, pictureIds, "old-sequential-get"));
                    }
                }

                // Raw samples retain actual execution order, not algorithm grouping.
                for (int pair = 0; pair < samplePairs; pair++) {
                    if ((pair & 1) == 0) {
                        samples.add(oldSamples.get(pair));
                        samples.add(batchSamples.get(pair));
                    } else {
                        samples.add(batchSamples.get(pair));
                        samples.add(oldSamples.get(pair));
                    }
                }

                Statistics old = summarize(oldSamples);
                Statistics batch = summarize(batchSamples);
                statistics.add(old);
                statistics.add(batch);
                comparisons.add(new Comparison(delayMs, pictureCount,
                        old.meanMs() / batch.meanMs(),
                        (1.0 - batch.meanMs() / old.meanMs()) * 100.0,
                        old.requestsPerOperation(), batch.requestsPerOperation()));

                System.out.printf(Locale.ROOT,
                        "visual-benchmark: stubDelay=%dms pictures=%d oldMean=%.3fms batchMean=%.3fms "
                                + "oldP95=%.3fms batchP95=%.3fms requests=%d->%d%n",
                        delayMs, pictureCount, old.meanMs(), batch.meanMs(), old.p95Ms(), batch.p95Ms(),
                        old.requestsPerOperation(), batch.requestsPerOperation());
            }
        }

        assertEquals(0, UNEXPECTED_REQUESTS.get(), "Stub received unexpected requests");

        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("recordedAtUtc", Instant.now().toString());
        environment.put("javaVersion", System.getProperty("java.version"));
        environment.put("javaVm", System.getProperty("java.vm.name"));
        environment.put("osName", System.getProperty("os.name"));
        environment.put("osVersion", System.getProperty("os.version"));
        environment.put("osArch", System.getProperty("os.arch"));
        environment.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        environment.put("endpoint", "http://127.0.0.1:<ephemeral-port>");
        environment.put("clientType", imageClient.getClass().getName());

        Map<String, Object> methodology = new LinkedHashMap<>();
        methodology.put("scope", "Remote visual-reference resolution only; not replaceVisuals end-to-end");
        methodology.put("server", "JDK HttpServer fixture, not real image-service or database");
        methodology.put("client", "Actual production ImageClient Spring Cloud OpenFeign bean and JSON codecs");
        methodology.put("concurrency", 1);
        methodology.put("warmupPairsPerCase", warmupPairs);
        methodology.put("measuredPairsPerCase", samplePairs);
        methodology.put("order", "Alternating old-first and batch-first within each case");
        methodology.put("timing", "System.nanoTime around complete synchronous client resolution and checks");
        methodology.put("quantiles", "Nearest-rank p50 and p95 of measured operation latencies");
        methodology.put("connectionPolicy", "Server sends Connection: close for every old/new response; "
                + "avoids loopback delayed-ACK artifacts but does not model production connection reuse");
        methodology.put("injectedDelay", "0ms loopback baseline plus " + injectedDelayMs
                + "ms artificial server sleep per HTTP request; not measured WAN/Wi-Fi RTT");
        methodology.put("bytes", "Actual UTF-8 JSON request/response body bytes only; excludes HTTP headers, "
                + "TCP/TLS overhead and picture binaries");
        methodology.put("fixture", "All pictures AVAILABLE and all annotation sets exist; public responses "
                + "include picture metadata and one circle per annotation set; batch returns identity fields only");
        methodology.put("excluded", List.of("Image controller/service/SQL", "History writes", "MinIO",
                "Nacos discovery", "Gateway", "TLS", "concurrent load", "throughput/QPS conclusions"));

        BenchmarkReport report = new BenchmarkReport(environment, methodology, statistics, comparisons, samples);
        Path outputDirectory = Path.of("target", "benchmark");
        Files.createDirectories(outputDirectory);
        Path reportPath = outputDirectory.resolve("visual-reference-batch.json");
        Files.writeString(reportPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8);

        StringBuilder csv = new StringBuilder("stub_delay_ms,picture_count,pair,algorithm,elapsed_nanos,"
                + "http_requests,request_json_bytes,response_json_bytes\n");
        for (Sample sample : samples) {
            csv.append(sample.stubDelayMs()).append(',').append(sample.pictureCount()).append(',')
                    .append(sample.pair()).append(',').append(sample.algorithm()).append(',')
                    .append(sample.elapsedNanos()).append(',').append(sample.httpRequests()).append(',')
                    .append(sample.requestJsonBytes()).append(',').append(sample.responseJsonBytes()).append('\n');
        }
        Files.writeString(outputDirectory.resolve("visual-reference-batch-samples.csv"), csv,
                StandardCharsets.UTF_8);
        System.out.println("visual-benchmark report: " + reportPath.toAbsolutePath());
    }

    private Sample measure(int pair, int delayMs, List<UUID> pictureIds, String algorithm) {
        Counters before = counters();
        long start = System.nanoTime();
        if ("old-sequential-get".equals(algorithm)) {
            runOld(pictureIds);
        } else {
            runBatch(pictureIds);
        }
        long elapsed = System.nanoTime() - start;
        Counters after = counters();
        long expectedRequests = "old-sequential-get".equals(algorithm) ? pictureIds.size() * 2L : 1L;
        assertEquals(expectedRequests, after.requests() - before.requests());
        return new Sample(pair, delayMs, pictureIds.size(), algorithm, elapsed,
                after.requests() - before.requests(),
                after.requestBytes() - before.requestBytes(),
                after.responseBytes() - before.responseBytes());
    }

    private void runOld(List<UUID> pictureIds) {
        Fixture fixture = ACTIVE_FIXTURE.get();
        for (UUID pictureId : pictureIds) {
            ImageClient.PictureLookupResponse picture = imageClient.getPicture(USER_ID, pictureId);
            assertNotNull(picture);
            assertEquals(pictureId, picture.pictureId());
            assertEquals(fixture.sources.get(pictureId), picture.sourceType());
            assertEquals("AVAILABLE", picture.uploadStatus());
            if (picture.sourceType() != VisualSourceType.STANDARD_REFERENCE) {
                assertEquals(USER_ID, picture.ownerUserId());
            }
            ImageClient.AnnotationSetLookupResponse annotation =
                    imageClient.getAnnotationSet(USER_ID, pictureId, ASSESSMENT_ID);
            assertNotNull(annotation);
            assertEquals(pictureId, annotation.pictureId());
            assertEquals(fixture.annotationIds.get(pictureId), annotation.annotationSetId());
        }
    }

    private void runBatch(List<UUID> pictureIds) {
        Fixture fixture = ACTIVE_FIXTURE.get();
        List<ImageClient.ResolvedVisualReference> resolved = imageClient.resolveVisualReferences(
                USER_ID, new ImageClient.ResolveVisualReferencesRequest(ASSESSMENT_ID, pictureIds));
        assertNotNull(resolved);
        assertEquals(pictureIds.size(), resolved.size());
        for (int index = 0; index < pictureIds.size(); index++) {
            UUID pictureId = pictureIds.get(index);
            ImageClient.ResolvedVisualReference visual = resolved.get(index);
            assertEquals(pictureId, visual.pictureId());
            assertEquals(fixture.sources.get(pictureId), visual.sourceType());
            assertEquals(fixture.annotationIds.get(pictureId), visual.annotationSetId());
        }
    }

    private static Counters counters() {
        return new Counters(REQUESTS.get(), REQUEST_JSON_BYTES.get(), RESPONSE_JSON_BYTES.get());
    }

    private static Statistics summarize(List<Sample> samples) {
        long[] durations = samples.stream().mapToLong(Sample::elapsedNanos).sorted().toArray();
        Sample first = samples.getFirst();
        double meanMs = samples.stream().mapToLong(Sample::elapsedNanos).average().orElseThrow() / 1_000_000.0;
        for (Sample sample : samples) {
            assertEquals(first.httpRequests(), sample.httpRequests());
            assertEquals(first.requestJsonBytes(), sample.requestJsonBytes());
            assertEquals(first.responseJsonBytes(), sample.responseJsonBytes());
        }
        return new Statistics(first.stubDelayMs(), first.pictureCount(), first.algorithm(), samples.size(),
                meanMs, percentile(durations, 0.50), percentile(durations, 0.95),
                durations[0] / 1_000_000.0, durations[durations.length - 1] / 1_000_000.0,
                first.httpRequests(), first.requestJsonBytes(), first.responseJsonBytes());
    }

    private static double percentile(long[] sortedNanos, double quantile) {
        return sortedNanos[(int) Math.ceil(sortedNanos.length * quantile) - 1] / 1_000_000.0;
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", VisualReferenceBatchBenchmarkTest::handle);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        byte[] request = exchange.getRequestBody().readAllBytes();
        REQUESTS.incrementAndGet();
        REQUEST_JSON_BYTES.addAndGet(request.length);
        byte[] response;
        int status = 200;
        try {
            Fixture fixture = ACTIVE_FIXTURE.get();
            if (fixture == null
                    || !USER_ID.toString().equals(exchange.getRequestHeaders().getFirst("X-User-Id"))) {
                throw new IllegalArgumentException("Missing fixture or unexpected user header");
            }
            delay(fixture.delayMs);
            response = fixture.response(exchange, request);
        } catch (RuntimeException exception) {
            UNEXPECTED_REQUESTS.incrementAndGet();
            status = 500;
            response = "{\"code\":\"UNEXPECTED_BENCHMARK_REQUEST\"}".getBytes(StandardCharsets.UTF_8);
        }
        RESPONSE_JSON_BYTES.addAndGet(response.length);
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.getResponseHeaders().set("Connection", "close");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
        } finally {
            exchange.close();
        }
    }

    private static void delay(int millis) {
        long until = System.nanoTime() + millis * 1_000_000L;
        long remaining;
        while ((remaining = until - System.nanoTime()) > 0) {
            LockSupport.parkNanos(remaining);
        }
    }

    private static final class Fixture {
        private final ObjectMapper mapper;
        private final List<UUID> pictureIds = new ArrayList<>();
        private final Map<UUID, UUID> annotationIds = new HashMap<>();
        private final Map<UUID, VisualSourceType> sources = new HashMap<>();
        private final Map<UUID, byte[]> pictureBodies = new HashMap<>();
        private final Map<UUID, byte[]> annotationBodies = new HashMap<>();
        private final Map<Integer, byte[]> batchBodies = new HashMap<>();
        private volatile int delayMs;

        private Fixture(ObjectMapper mapper) {
            this.mapper = mapper;
            for (int index = 1; index <= 12; index++) {
                UUID pictureId = UUID.fromString("30000000-0000-0000-0000-%012d".formatted(index));
                UUID annotationId = UUID.fromString("40000000-0000-0000-0000-%012d".formatted(index));
                VisualSourceType source = VisualSourceType.values()[(index - 1) % VisualSourceType.values().length];
                pictureIds.add(pictureId);
                annotationIds.put(pictureId, annotationId);
                sources.put(pictureId, source);
                pictureBodies.put(pictureId, encode(picture(pictureId, source)));
                annotationBodies.put(pictureId, encode(annotation(pictureId, annotationId)));
            }
            for (int count : List.of(1, 6, 12)) {
                List<Map<String, Object>> resolved = new ArrayList<>();
                for (UUID pictureId : pictureIds.subList(0, count)) {
                    resolved.add(Map.of("pictureId", pictureId, "sourceType", sources.get(pictureId),
                            "annotationSetId", annotationIds.get(pictureId)));
                }
                batchBodies.put(count, encode(resolved));
            }
        }

        private byte[] response(HttpExchange exchange, byte[] request) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(method) && BATCH_PATH.equals(path)) {
                ImageClient.ResolveVisualReferencesRequest decoded = mapper.readValue(
                        request, ImageClient.ResolveVisualReferencesRequest.class);
                if (!ASSESSMENT_ID.equals(decoded.assessmentId()) || decoded.pictureIds() == null
                        || !batchBodies.containsKey(decoded.pictureIds().size())
                        || !pictureIds.subList(0, decoded.pictureIds().size()).equals(decoded.pictureIds())) {
                    throw new IllegalArgumentException("Unexpected batch payload");
                }
                return batchBodies.get(decoded.pictureIds().size());
            }
            if (!"GET".equals(method) || !path.startsWith(PICTURE_PREFIX) || request.length != 0) {
                throw new IllegalArgumentException("Unexpected method, path or body");
            }
            String suffix = path.substring(PICTURE_PREFIX.length());
            boolean annotation = suffix.endsWith("/annotation-set");
            UUID pictureId = UUID.fromString(annotation
                    ? suffix.substring(0, suffix.length() - "/annotation-set".length()) : suffix);
            if (!pictureBodies.containsKey(pictureId)) {
                throw new IllegalArgumentException("Unexpected picture ID");
            }
            if (annotation) {
                if (!("assessmentId=" + ASSESSMENT_ID).equals(exchange.getRequestURI().getRawQuery())) {
                    throw new IllegalArgumentException("Unexpected assessment query");
                }
                return annotationBodies.get(pictureId);
            }
            if (exchange.getRequestURI().getRawQuery() != null) {
                throw new IllegalArgumentException("Unexpected picture query");
            }
            return pictureBodies.get(pictureId);
        }

        private Map<String, Object> picture(UUID pictureId, VisualSourceType source) {
            Map<String, Object> picture = new LinkedHashMap<>();
            picture.put("pictureId", pictureId);
            picture.put("ownerUserId", source == VisualSourceType.STANDARD_REFERENCE ? null : USER_ID);
            picture.put("sourceType", source);
            picture.put("standardAssetKey", source == VisualSourceType.STANDARD_REFERENCE ? "hand-right-palm-v1" : null);
            picture.put("standardHandSide", source == VisualSourceType.STANDARD_REFERENCE ? "RIGHT" : null);
            picture.put("standardView", source == VisualSourceType.STANDARD_REFERENCE ? "PALMAR" : null);
            picture.put("originalFilename", "hand-" + pictureId + ".jpg");
            picture.put("mediaType", "image/jpeg");
            picture.put("byteSize", 245760);
            picture.put("width", 1200);
            picture.put("height", 800);
            picture.put("uploadStatus", "AVAILABLE");
            picture.put("processingStatus", "PENDING");
            picture.put("originalUrl", "https://storage.example.invalid/symptom-pictures/" + pictureId
                    + "/original.jpg?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Date=20261025T000000Z"
                    + "&X-Amz-Expires=900&X-Amz-Signature=0123456789abcdef0123456789abcdef");
            picture.put("thumbnailUrl", null);
            picture.put("viewState", source == VisualSourceType.MODEL_SNAPSHOT
                    ? Map.of("modelAssetVersion", "hand-v1", "camera", Map.of("x", 0, "y", 0, "z", 3),
                            "target", Map.of("x", 0, "y", 0, "z", 0), "up", Map.of("x", 0, "y", 1, "z", 0),
                            "zoom", 1.0, "coarseRegionId", "right.middle.pip") : null);
            picture.put("createdAt", "2026-10-25T00:00:00Z");
            picture.put("updatedAt", "2026-10-25T00:00:00Z");
            return picture;
        }

        private Map<String, Object> annotation(UUID pictureId, UUID annotationId) {
            return Map.of("annotationSetId", annotationId, "pictureId", pictureId,
                    "coordinateSpace", "NORMALIZED_0_1", "sourceWidth", 1200, "sourceHeight", 800,
                    "shapes", List.of(Map.of("shapeId", annotationId, "type", "CIRCLE",
                            "geometry", Map.of("kind", "circle", "center", Map.of("x", 0.5, "y", 0.4),
                                    "radiusX", 0.1, "radiusY", 0.12),
                            "color", "#FF0000", "lineWidthNormalized", 0.005, "label", "不适位置")),
                    "note", "右手中指不适", "version", 2, "updatedAt", "2026-10-25T00:00:00Z");
        }

        private byte[] encode(Object value) {
            return mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
        }
    }

    private record Counters(long requests, long requestBytes, long responseBytes) {}

    private record Sample(int pair, int stubDelayMs, int pictureCount, String algorithm,
                          long elapsedNanos, long httpRequests, long requestJsonBytes, long responseJsonBytes) {}

    private record Statistics(int stubDelayMs, int pictureCount, String algorithm, int samples,
                              double meanMs, double p50Ms, double p95Ms, double minMs, double maxMs,
                              long requestsPerOperation, long requestJsonBytesPerOperation,
                              long responseJsonBytesPerOperation) {}

    private record Comparison(int stubDelayMs, int pictureCount, double meanSpeedup,
                              double meanLatencyReductionPercent, long oldRequests, long batchRequests) {}

    private record BenchmarkReport(Map<String, Object> environment, Map<String, Object> methodology,
                                   List<Statistics> statistics, List<Comparison> comparisons,
                                   List<Sample> rawSamples) {}
}
