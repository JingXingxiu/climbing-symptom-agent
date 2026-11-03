package org.javaup.history.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SubmitAssessmentRequest(@NotNull @Positive Integer version) {
}
