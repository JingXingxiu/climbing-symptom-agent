package org.javaup.history.api.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Replaces the visual-source list while checking the current Assessment version.
 */
public record ReplaceVisualReferencesRequest(
        @NotNull @Size(max = 12) List<@NotNull UUID> pictureIds,
        @NotNull @Positive Integer version
) {
}
