package org.javaup.ai.observability;

import java.util.UUID;

/**
 * 请求或执行范围内的关联 ID；只在没有 Tracer 的测试/降级场景补位。
 * 不接受客户端自报的 X-Trace-Id，也不承担 W3C traceparent 的解析。
 */
public final class AgentTraceContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private AgentTraceContext() {
    }

    public static String currentTraceId() {
        return CURRENT.get();
    }

    public static String currentOrNew() {
        String current = CURRENT.get();
        return current != null ? current : newId();
    }

    public static Scope open(String traceId) {
        String previous = CURRENT.get();
        String selected = valid(traceId) ? traceId : previous;
        CURRENT.set(selected != null ? selected : newId());
        return new Scope(previous);
    }

    static boolean valid(String traceId) {
        return traceId != null
                && traceId.matches("[0-9a-f]{32}")
                && !traceId.equals("00000000000000000000000000000000");
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static final class Scope implements AutoCloseable {

        private final String previous;

        private Scope(String previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
