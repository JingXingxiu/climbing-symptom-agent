package org.javaup.ai.observability;

import io.micrometer.observation.Observation;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 替代默认 fallback Tracing handler，在记录原生模型 Span 错误前清理 SDK 异常。
 * 仅替换观测上下文，不改变模型调用抛给业务层的异常。
 * Boot 会先注册 Tracing handler 分组；普通最高优先级 handler 仍可能晚于该分组。
 */
@Component
@Order(MicrometerTracingAutoConfiguration.DEFAULT_TRACING_OBSERVATION_HANDLER_ORDER)
public final class SafeModelErrorObservationHandler extends DefaultTracingObservationHandler {

    public SafeModelErrorObservationHandler(ObjectProvider<Tracer> tracerProvider) {
        super(tracerProvider.getIfAvailable(() -> Tracer.NOOP));
    }

    @Override
    public void onError(Observation.Context context) {
        if ("gen_ai.client.operation".equals(context.getName()) && context.getError() != null) {
            context.setError(new SafeModelObservationException());
        }
        super.onError(context);
    }

    private static final class SafeModelObservationException extends RuntimeException {
        private SafeModelObservationException() {
            super("Model request failed", null, false, false);
        }
    }
}
