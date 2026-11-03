package org.javaup.ai.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

/** 版本和起点用于并发校验；旧的 expectedStepNumber 仅保留为可选显示序号。 */
public record AnswerAgentQuestionRequest(
        @NotNull UUID questionId,
        @NotNull JsonNode answer,
        @Min(0) Integer expectedStepNumber,
        @NotNull @Positive Long expectedVersion,
        @NotNull UUID baseCheckpointId) {}
