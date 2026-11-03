package org.javaup.ai.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** 创建分析任务；模型选择来自服务配置白名单，来源检查点用于重新分析。 */
public record CreateAgentSessionRequest(
        @NotNull UUID assessmentId,
        @Size(max = 20) String preferredLocale,
        @Size(max = 80) String modelProvider,
        @Size(max = 120) String modelName,
        UUID sourceSessionId,
        UUID sourceCheckpointId) {}
