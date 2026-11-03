package org.javaup.history.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.javaup.history.api.dto.HistoryEnums.CurrentStepKey;

/**
 * Optimistic update of Assessment basics.
 */
public record UpdateAssessmentBasicsRequest(
        @NotNull @Valid InitialSymptomDetails initialInput,
        CurrentStepKey currentStepKey,
        @NotNull @Positive Integer version
) {
}
