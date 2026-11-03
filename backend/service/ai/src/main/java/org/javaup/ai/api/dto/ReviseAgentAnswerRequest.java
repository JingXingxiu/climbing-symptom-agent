package org.javaup.ai.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

/** 从原回答之前分叉；不会覆盖旧回答及其后续节点。 */
public record ReviseAgentAnswerRequest(
        @NotNull UUID answerStepId,
        @NotNull JsonNode answer,
        @NotNull @Positive Long expectedVersion) {}
