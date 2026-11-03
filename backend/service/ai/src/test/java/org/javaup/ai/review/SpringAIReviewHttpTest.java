package org.javaup.ai.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.javaup.ai.api.dto.AgentEnums.AgentBehavior;
import org.javaup.ai.api.dto.AgentEnums.RecommendationLevel;
import org.javaup.ai.api.problem.ApiException;
import org.javaup.ai.model.AgentDecision;
import org.javaup.ai.model.AgentModelProperties;
import org.javaup.ai.model.AgentModelRequest;
import org.javaup.ai.model.SpringAICompatibleModel;
import org.javaup.ai.observability.AgentTelemetry;
import org.javaup.ai.rag.KnowledgeRetrievalService;
import org.javaup.ai.search.SearchTool;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real SDK serialization; the provider and pictures are local test servers only. */
class SpringAIReviewHttpTest {
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ObservationRegistry observations = ObservationRegistry.create();
    private final SearchTool search = mock(SearchTool.class);
    private final KnowledgeRetrievalService knowledge = mock(KnowledgeRetrievalService.class);
    private final SpringAICompatibleModel adapter = new SpringAICompatibleModel(
            new AgentModelProperties(), mapper, search, new AgentTelemetry(observations, meters), observations, knowledge);

    @Test
    void reviewUsesOriginalFactsAndPhotographBytesAndActualUsage() throws Exception {
        try (Provider provider = new Provider("{\"verdict\":\"ACCEPT\",\"issues\":[]}")) {
            AgentDecision candidate = candidate();
            ReviewResult result = adapter.review(request(provider.picture()), candidate, provider.profile());

            assertThat(result.verdict()).isEqualTo(ReviewVerdict.ACCEPT);
            assertThat(result.issues()).isEmpty();
            assertThat(result.providerRequestId()).isEqualTo("review-request");
            assertThat(result.inputTokens()).isEqualTo(30);
            assertThat(result.outputTokens()).isEqualTo(8);
            assertThat(provider.calls.get()).isEqualTo(1);
            String sent = provider.sent.get().toString();
            assertThat(sent).contains("MIDDLE", "PIP", "candidate", "verifiedReferences", "data:image/png;base64,AQID");
            assertThat(sent).doesNotContain("signature=PRIVATE", "http://127.0.0.1:");
            verifyNoInteractions(search, knowledge);
        }
    }

    @Test
    void malformedOrInconsistentReviewNeverPassesAndStillRecordsKnownUsage() throws Exception {
        List<String> invalid = new ArrayList<>(List.of(
                "{}",
                "{\"verdict\":\"UNKNOWN\",\"issues\":[]}",
                "{\"verdict\":\"REVISE\",\"issues\":[]}",
                "{\"verdict\":\"ACCEPT\",\"issues\":[{\"code\":\"OTHER\",\"reason\":\"problem\"}]}",
                "{\"verdict\":\"ABSTAIN\",\"issues\":[{\"code\":\"UNKNOWN\",\"reason\":\"problem\"}]}",
                "{\"verdict\":\"ACCEPT\",\"issues\":[],\"override\":true}",
                "{\"verdict\":\"REVISE\",\"issues\":[{\"code\":\"OTHER\",\"reason\":\"a\"},{\"code\":\"OTHER\",\"reason\":\"b\"}]}",
                "{\"verdict\":0,\"issues\":[]}",
                "{\"verdict\":\"REVISE\",\"issues\":[{\"code\":0,\"reason\":\"problem\"}]}",
                "{\"verdict\":\"REVISE\",\"issues\":[{\"code\":\"OTHER\",\"reason\":12}]}",
                "{\"verdict\":\"ACCEPT\",\"issues\":[]} {}",
                "{\"verdict\":\"REVISE\",\"verdict\":\"ACCEPT\",\"issues\":[]}"
        ));
        invalid.add(mapper.writeValueAsString(Map.of(
                "verdict", "REVISE", "issues", List.of(Map.of("code", "OTHER", "reason", "a".repeat(501))))));
        invalid.add(mapper.writeValueAsString(Map.of("verdict", "REVISE", "issues",
                List.of(ReviewIssueCode.INPUT_MISMATCH, ReviewIssueCode.UNRESOLVED_CONTRADICTION,
                        ReviewIssueCode.UNSUPPORTED_CLAIM, ReviewIssueCode.UNSAFE_ADVICE,
                        ReviewIssueCode.MISSING_UNCERTAINTY, ReviewIssueCode.VISUAL_MISMATCH).stream()
                        .map(code -> new ReviewIssue(code, "problem")).toList())));
        try (Provider provider = new Provider(invalid.getFirst())) {
            for (String raw : invalid) {
                provider.answer.set(raw);
                assertThatThrownBy(() -> adapter.review(request(null), candidate(), provider.profile()))
                        .isInstanceOf(ApiException.class)
                        .satisfies(error -> assertThat(((ApiException) error).code())
                                .isEqualTo("INVALID_QUALITY_REVIEW_OUTPUT"));
            }
            assertThat(provider.calls.get()).isEqualTo(invalid.size());
            assertThat(meters.get("agent.tokens").tag("provider", "qwen").tag("model", "reviewer")
                    .tag("direction", "input").counter().count()).isEqualTo(30.0 * invalid.size());
        }
    }

    @Test
    void oneCorrectionKeepsTheOriginalSourcesAndDoesNotRetrieveAgain() throws Exception {
        AgentDecision original = candidate();
        AgentDecision corrected = new AgentDecision(
                AgentBehavior.PRODUCE_RESULT, "Corrected with uncertainty", null, mapper.createObjectNode(), List.of(),
                original.outcome(), List.of(new AgentDecision.Reference("Invented", "https://invented.invalid", "fake")));
        try (Provider provider = new Provider(mapper.writeValueAsString(corrected))) {
            var result = adapter.revise(
                    request(null), original,
                    List.of(new ReviewIssue(ReviewIssueCode.MISSING_UNCERTAINTY, "State the missing information")),
                    provider.profile());

            assertThat(provider.calls.get()).isEqualTo(1);
            assertThat(result.decision().references()).containsExactlyElementsOf(original.references());
            assertThat(provider.sent.get().toString()).contains("reviewIssues", "MISSING_UNCERTAINTY", "verifiedReferences");
            verifyNoInteractions(search, knowledge);
        }
    }

    @Test
    void providerFailureIsOneRequestAndDoesNotTurnIntoApproval() throws Exception {
        try (Provider provider = new Provider("unused")) {
            provider.status.set(500);
            assertThatThrownBy(() -> adapter.review(request(null), candidate(), provider.profile()))
                    .isInstanceOf(ApiException.class)
                    .satisfies(error -> {
                        ApiException failure = (ApiException) error;
                        assertThat(failure.code()).isEqualTo("QUALITY_REVIEW_CALL_FAILED");
                        assertThat(failure.getMessage()).doesNotContain("PRIVATE_PROVIDER_DETAIL");
                    });
            assertThat(provider.calls.get()).isEqualTo(1);
        }
    }

    private AgentModelRequest request(URI picture) {
        JsonNode input = mapper.readTree("{\"assessment\":{\"symptomLocations\":[{\"fingerCode\":\"MIDDLE\",\"jointCode\":\"PIP\"}]}}");
        return new AgentModelRequest(UUID.randomUUID(), "zhipu", "primary", "zh-CN", input,
                mapper.createObjectNode(), List.of(), picture == null ? List.of() : List.of(
                        new AgentModelRequest.Picture(UUID.randomUUID(), picture, "image/png")));
    }

    private AgentDecision candidate() {
        return new AgentDecision(
                AgentBehavior.PRODUCE_RESULT, "Symptoms summarized", null, mapper.createObjectNode(), List.of(),
                new AgentDecision.OutcomeDraft(RecommendationLevel.SELF_MONITOR,
                        "Uncertain hand discomfort", List.of("Only describes reported symptoms"),
                        List.of("Seek professional help if needed"), List.of(), "Information is limited"),
                List.of(new AgentDecision.Reference("Actual source", "https://source.invalid", "Reference passage")));
    }

    private final class Provider implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> answer;
        private final AtomicReference<JsonNode> sent = new AtomicReference<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger status = new AtomicInteger(200);

        private Provider(String answer) throws Exception {
            this.answer = new AtomicReference<>(answer);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/picture", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, 3);
                exchange.getResponseBody().write(new byte[] {1, 2, 3});
                exchange.close();
            });
            server.createContext("/v1/chat/completions", exchange -> {
                calls.incrementAndGet();
                sent.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
                byte[] bytes = status.get() != 200
                        ? "{\"error\":{\"message\":\"PRIVATE_PROVIDER_DETAIL\",\"type\":\"server_error\"}}".getBytes(StandardCharsets.UTF_8)
                        : mapper.writeValueAsBytes(Map.of(
                                "id", "review-request", "object", "chat.completion", "created", 1, "model", "reviewer",
                                "choices", List.of(Map.of("index", 0, "message",
                                        Map.of("role", "assistant", "content", this.answer.get()), "finish_reason", "stop")),
                                "usage", Map.of("prompt_tokens", 30, "completion_tokens", 8, "total_tokens", 38)));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        private URI picture() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/picture?signature=PRIVATE");
        }

        private AgentModelProperties.Profile profile() {
            return new AgentModelProperties.Profile("qwen", "reviewer",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
