package org.javaup.ai.model;

/** 公开业务决策和适度计费元数据；不返回、保存模型隐藏推理。 */
public record AgentModelResult(
        AgentDecision decision,
        String providerRequestId,
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens
) {}
