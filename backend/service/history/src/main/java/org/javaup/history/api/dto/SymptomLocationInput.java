package org.javaup.history.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.javaup.history.api.dto.HistoryEnums.FingerCode;
import org.javaup.history.api.dto.HistoryEnums.HandSide;
import org.javaup.history.api.dto.HistoryEnums.JointCode;

/**
 * One coarse symptom location supplied by the user. Palm and wrist locations
 * are represented by {@code regionId}, never as finger or joint enum values.
 */
public record SymptomLocationInput(
        @NotNull HandSide handSide,
        @NotBlank @Pattern(regexp = "^hand\\.(left|right)\\.[a-z0-9.-]+$") String regionId,
        @NotBlank @Size(max = 120) String displayLabel,
        FingerCode fingerCode,
        JointCode jointCode,
        @Size(max = 500) String detailNote
) {
}
