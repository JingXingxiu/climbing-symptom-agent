package org.javaup.ai.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AgentTelemetryTest {

    private final ObservationRegistry observations = ObservationRegistry.create();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<Observation.Context> stopped = new ArrayList<>();
    private final AgentTelemetry telemetry = new AgentTelemetry(observations, meters);

    @BeforeEach
    void recordObservations() {
        observations.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meters))
                .observationHandler(new ObservationHandler<Observation.Context>() {
                    @Override
                    public boolean supportsContext(Observation.Context context) {
                        return true;
                    }

                    @Override
                    public void onStop(Observation.Context context) {
                        stopped.add(context);
                    }
                });
    }

    @Test
    void sessionHasStableCorrelationAndDoesNotUseSessionAsMetricTag() {
        UUID sessionId = UUID.randomUUID();
        assertThat(telemetry.currentTraceId()).isNull();
        telemetry.inSession(sessionId, () -> {
            String traceId = telemetry.currentTraceId();
            assertThat(traceId).matches("[a-f0-9]{32}");
            return telemetry.observe("web_search", () -> {
                assertThat(telemetry.currentTraceId()).isEqualTo(traceId);
                return "ok";
            });
        });
        assertThat(telemetry.currentTraceId()).isNull();
        assertThat(stopped).anySatisfy(context -> {
            assertThat(context.getName()).isEqualTo("agent.execution");
            assertThat(context.getHighCardinalityKeyValue("agent.session.id").getValue())
                    .isEqualTo(sessionId.toString());
        });
        assertThat(meters.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).noneSatisfy(tag ->
                        assertThat(tag.getValue()).isEqualTo(sessionId.toString())));
        assertThat(meters.get("agent.web_search").timer().count()).isEqualTo(1);
    }

    @Test
    void originalFailurePropagatesButSensitiveMessageIsNotSentToSpan() {
        IllegalStateException failure = new IllegalStateException("private photo?token=secret");
        assertThatThrownBy(() -> telemetry.inSession(UUID.randomUUID(), () ->
                telemetry.observe("model_request", Map.of("prompt", "user-private-text"), () -> {
                    throw failure;
                }))).isSameAs(failure);
        assertThat(telemetry.currentTraceId()).isNull();
        assertThat(stopped).allSatisfy(context -> {
            assertThat(context.getError().getMessage()).isEqualTo("Agent stage failed");
            assertThat(context.getLowCardinalityKeyValues().toString()).doesNotContain("user-private");
        });
        assertThat(meters.get("agent.model_request").tag("outcome", "error").timer().count())
                .isEqualTo(1);
    }

    @Test
    void countsOutcomesAndKnownTokenUsageWithoutInventingUnknownUsage() {
        telemetry.recordModelOutcome("SUCCEEDED");
        telemetry.recordModelOutcome("DISCARDED");
        telemetry.recordDecision("CLARIFY_CONTRADICTION");
        telemetry.recordTokens("zhipu", "glm-test", 100, 20);
        telemetry.recordTokens("zhipu", "glm-test", null, -1);
        assertThat(meters.get("agent.model.calls").tag("outcome", "succeeded").counter().count())
                .isEqualTo(1);
        assertThat(meters.get("agent.model.calls").tag("outcome", "discarded").counter().count())
                .isEqualTo(1);
        assertThat(meters.get("agent.decisions").tag("behavior", "clarify_contradiction")
                .counter().count()).isEqualTo(1);
        assertThat(meters.get("agent.tokens").tag("direction", "input").counter().count())
                .isEqualTo(100);
        assertThat(meters.get("agent.tokens").tag("direction", "output").counter().count())
                .isEqualTo(20);
    }

    @Test
    void requestDoesNotTrustClientTraceIdAndCleansUpAfterFailure() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "attacker-controlled");
        MockHttpServletResponse response = new MockHttpServletResponse();
        TraceCorrelationFilter filter = new TraceCorrelationFilter(telemetry);
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            assertThat(AgentTraceContext.currentTraceId()).isEqualTo(response.getHeader("X-Trace-Id"));
            throw new IllegalStateException("failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(response.getHeader("X-Trace-Id")).matches("[a-f0-9]{32}");
        assertThat(AgentTraceContext.currentTraceId()).isNull();
    }
}
