package org.javaup.ai.api.dto;

import jakarta.validation.constraints.NotBlank;

public record AgentQuestionOption(
        @NotBlank String value,
        @NotBlank String label
) {
}
