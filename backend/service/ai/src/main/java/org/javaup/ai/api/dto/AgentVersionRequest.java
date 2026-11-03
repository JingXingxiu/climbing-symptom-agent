package org.javaup.ai.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 修改当前任务时携带上一次读取的 Session 版本。 */
public record AgentVersionRequest(@NotNull @Positive Long expectedVersion) {}
