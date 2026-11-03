package org.javaup.ai.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ChangeAgentModelRequest(
        @NotNull @Positive Long expectedVersion,
        @NotBlank String provider,
        @NotBlank String model) {}
