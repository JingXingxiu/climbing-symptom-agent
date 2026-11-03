package org.javaup.history.api.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.javaup.history.api.dto.HistoryEnums.HandSide;
import org.javaup.history.api.dto.HistoryEnums.NormalUseImpact;
import org.javaup.history.api.dto.HistoryEnums.OnsetKind;
import org.javaup.history.api.dto.HistoryEnums.SymptomType;

/**
 * First structured user input for an Assessment. Symptom types deliberately
 * remain multi-select and are not reduced to a pain-only field.
 */
public record InitialSymptomDetails(
        @NotNull HandSide handSide,
        @Size(min = 1, max = 8) List<SymptomType> symptomTypes,
        @NotNull OnsetKind onsetKind,
        @Positive Integer onsetAmount,
        LocalDate onsetDate,
        @Size(max = 200) String onsetText,
        @NotBlank @Size(max = 500) String activityContext,
        @NotNull NormalUseImpact normalUseImpact,
        @Size(max = 2000) String userNote
) {
}
