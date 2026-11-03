package org.javaup.ai.observability;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 在框架 HTTP Observation 启动后，将同一关联 ID 用于响应、日志和 Problem。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceCorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";

    private final AgentTelemetry telemetry;

    public TraceCorrelationFilter(AgentTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        String previous = MDC.get("traceId");
        try (AgentTraceContext.Scope ignored = AgentTraceContext.open(telemetry.currentTraceId())) {
            String traceId = AgentTraceContext.currentTraceId();
            response.setHeader(HEADER, traceId);
            MDC.put("traceId", traceId);
            chain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove("traceId");
            } else {
                MDC.put("traceId", previous);
            }
        }
    }
}
