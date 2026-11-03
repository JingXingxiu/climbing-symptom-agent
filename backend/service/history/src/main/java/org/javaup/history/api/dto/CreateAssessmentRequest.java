package org.javaup.history.api.dto;

import jakarta.validation.Valid;

/**
 * An Assessment draft may be created before first input is complete.
 */
public record CreateAssessmentRequest(@Valid InitialSymptomDetails initialInput) {
}
