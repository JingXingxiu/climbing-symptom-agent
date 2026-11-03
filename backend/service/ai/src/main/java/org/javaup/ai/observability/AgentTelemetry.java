package org.javaup.ai.observability;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Agent 的阶段计时、Span 与少量业务指标。
 * 任务 ID 仅作为高基数 Span 属性；指标标签不承载用户文本、图片 URL 或身份。
 */
@Component
public class AgentTelemetry {

    private static final Set<String> TAG_KEYS = Set.of("provider", "model", "status", "source");
    private static final Set<String> OUTCOMES = Set.of("succeeded", "failed", "discarded");
    private static final Set<String> BEHAVIORS =
            Set.of("narrow_down", "clarify_contradiction", "produce_result");

    private final ObservationRegistry observations;
    private final MeterRegistry meters;
    private final Supplier<Tracer> tracerSupplier;

    @Autowired
    public AgentTelemetry(
            ObservationRegistry observations,
            MeterRegistry meters,
            ObjectProvider<Tracer> tracerProvider
    ) {
        this(observations, meters, tracerProvider::getIfAvailable);
    }

    /** 独立单元测试无需启动 Tracer 或外部 Collector。 */
    public AgentTelemetry(ObservationRegistry observations, MeterRegistry meters) {
        this(observations, meters, () -> null);
    }

    private AgentTelemetry(
            ObservationRegistry observations,
            MeterRegistry meters,
            Supplier<Tracer> tracerSupplier
    ) {
        this.observations = observations;
        this.meters = meters;
        this.tracerSupplier = tracerSupplier;
    }

    public <T> T observe(String stage, Supplier<T> action) {
        return observe(stage, Map.of(), action);
    }

    public <T> T observe(
            String stage,
            Map<String, String> lowCardinalityTags,
            Supplier<T> action
    ) {
        Observation observation = Observation.createNotStarted(
                "agent." + safe(stage), observations);
        lowCardinalityTags.forEach((key, value) -> {
            if (TAG_KEYS.contains(key)) {
                observation.lowCardinalityKeyValue(key, safe(value));
            }
        });
        return execute(observation, action);
    }

    public <T> T inSession(UUID sessionId, Supplier<T> action) {
        Observation execution = Observation.createNotStarted("agent.execution", observations);
        if (sessionId != null) {
            execution.highCardinalityKeyValue("agent.session.id", sessionId.toString());
        }
        try (AgentTraceContext.Scope ignored = AgentTraceContext.open(currentTraceId())) {
            return execute(execution, action);
        }
    }

    /** 当前请求/执行内稳定；没有执行上下文时返回 null。 */
    public String currentTraceId() {
        Tracer tracer = tracerSupplier.get();
        Span current = tracer == null ? null : tracer.currentSpan();
        String tracedId = current == null ? null : current.context().traceId();
        return AgentTraceContext.valid(tracedId)
                ? tracedId
                : AgentTraceContext.currentTraceId();
    }

    public void recordDecision(String behavior) {
        String normalized = normalize(behavior);
        meters.counter("agent.decisions", "behavior",
                BEHAVIORS.contains(normalized) ? normalized : "other").increment();
    }

    public void recordModelOutcome(String outcome) {
        String normalized = normalize(outcome);
        meters.counter("agent.model.calls", "outcome",
                OUTCOMES.contains(normalized) ? normalized : "other").increment();
    }

    public void recordTokens(String provider, String model, Integer input, Integer output) {
        incrementTokens(provider, model, "input", input);
        incrementTokens(provider, model, "output", output);
    }

    private void incrementTokens(String provider, String model, String direction, Integer count) {
        if (count != null && count > 0) {
            meters.counter("agent.tokens", "provider", safe(provider), "model", safe(model),
                    "direction", direction).increment(count);
        }
    }

    private <T> T execute(Observation observation, Supplier<T> action) {
        observation.start();
        try (Observation.Scope ignored = observation.openScope()) {
            T value = action.get();
            observation.lowCardinalityKeyValue("outcome", "success");
            return value;
        } catch (RuntimeException | Error failure) {
            observation.lowCardinalityKeyValue("outcome", "error");
            // 不把异常 message（可能含用户输入/签名 URL）作为 Span 事件发送出去。
            observation.error(new IllegalStateException("Agent stage failed"));
            throw failure;
        } finally {
            observation.stop();
        }
    }

    private static String normalize(String value) {
        return value == null ? "other" : value.toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value != null && !value.contains("://")
                && value.matches("[a-zA-Z0-9_.:/-]{1,100}")
                ? value : "other";
    }
}
