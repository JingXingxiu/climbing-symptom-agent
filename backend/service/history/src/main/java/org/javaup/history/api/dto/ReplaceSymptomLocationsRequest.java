package org.javaup.history.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Replaces the complete ordered set of coarse locations for an Assessment. */
public record ReplaceSymptomLocationsRequest(
        @NotNull @Size(max = 20) List<@Valid @NotNull SymptomLocationInput> symptomLocations
) {
}
