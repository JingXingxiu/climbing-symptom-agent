package org.javaup.ai.model;

/** 可替换模型边界；每次 analyze 恰好一次模型请求，不在内部无限自动循环。 */
public interface AgentModelClient {

    void requireAvailable(
            String provider,
            String model
    );

    AgentModelResult analyze(AgentModelRequest request);
}
