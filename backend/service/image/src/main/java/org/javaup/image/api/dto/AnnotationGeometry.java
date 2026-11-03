package org.javaup.image.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The OpenAPI one-of geometry. A structured annotation is retained instead of
 * permanently altering the user's only source image.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = AnnotationGeometry.Point.class, name = "point"),
        @JsonSubTypes.Type(value = AnnotationGeometry.Circle.class, name = "circle"),
        @JsonSubTypes.Type(value = AnnotationGeometry.Polyline.class, name = "polyline"),
        @JsonSubTypes.Type(value = AnnotationGeometry.Polygon.class, name = "polygon")
})
public sealed interface AnnotationGeometry permits AnnotationGeometry.Point, AnnotationGeometry.Circle,
        AnnotationGeometry.Polyline, AnnotationGeometry.Polygon {

    record Point(@NotNull @Valid NormalizedPoint point) implements AnnotationGeometry {
    }

    record Circle(
            @NotNull @Valid NormalizedPoint center,
            @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("1.0") double radiusX,
            @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("1.0") double radiusY
    ) implements AnnotationGeometry {
    }

    record Polyline(@NotNull @Size(min = 2, max = 2000) List<@NotNull @Valid NormalizedPoint> points)
            implements AnnotationGeometry {
    }

    record Polygon(@NotNull @Size(min = 2, max = 2000) List<@NotNull @Valid NormalizedPoint> points)
            implements AnnotationGeometry {
    }
}
