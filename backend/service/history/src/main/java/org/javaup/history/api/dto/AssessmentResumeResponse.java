package org.javaup.history.api.dto;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.javaup.history.api.dto.HistoryEnums.CurrentStepKey;

public record AssessmentResumeResponse(
        @NotNull @Valid AssessmentResponse assessment,
        @NotNull CurrentStepKey currentStepKey,
        UUID activeAgentSessionId
) {
}
