package org.javaup.image.api.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Whole-set replacement request. Version zero is reserved for first creation.
 */
public record ReplaceAnnotationSetRequest(
        @NotNull UUID assessmentId,
        @NotNull @Positive Integer sourceWidth,
        @NotNull @Positive Integer sourceHeight,
        @NotNull @Size(max = 100) List<@NotNull @Valid AnnotationShape> shapes,
        @Size(max = 1000) String note,
        @NotNull @Min(0) Integer version
) {
}
