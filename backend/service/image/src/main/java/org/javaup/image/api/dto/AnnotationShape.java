package org.javaup.image.api.dto;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A single normalized visual annotation.
 */
public record AnnotationShape(
        @NotNull UUID shapeId,
        @NotNull AnnotationShapeType type,
        @NotNull @Valid AnnotationGeometry geometry,
        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String color,
        @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("0.05") Double lineWidthNormalized,
        @Size(max = 120) String label
) {
}
