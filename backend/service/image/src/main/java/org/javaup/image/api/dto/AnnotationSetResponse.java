package org.javaup.image.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.javaup.image.entity.AnnotationSet;
import org.javaup.image.entity.PictureAnnotation;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Versioned annotation set for a picture and Assessment context.
 */
public record AnnotationSetResponse(
        @NotNull UUID annotationSetId,
        @NotNull UUID pictureId,
        @NotNull CoordinateSpace coordinateSpace,
        @Positive int sourceWidth,
        @Positive int sourceHeight,
        @NotNull @Size(max = 100) List<@Valid AnnotationShape> shapes,
        @Size(max = 1000) String note,
        @Positive int version,
        @NotNull Instant updatedAt
) {
    public static AnnotationSetResponse fromEntity(
            AnnotationSet entity,
            List<PictureAnnotation> annotations,
            ObjectMapper objectMapper
    ) {
        List<AnnotationShape> shapes = annotations.stream()
                .map(annotation -> {
                    Map<String, Object> style = objectMapper.readValue(
                            annotation.getStyle().getValue(),
                            new TypeReference<Map<String, Object>>() {}
                    );
                    Number lineWidth = (Number) style.get("lineWidthNormalized");

                    return new AnnotationShape(
                            annotation.getId(),
                            AnnotationShapeType.valueOf(annotation.getShapeType()),
                            objectMapper.readValue(
                                    annotation.getGeometry().getValue(),
                                    AnnotationGeometry.class
                            ),
                            (String) style.get("color"),
                            lineWidth == null ? null : lineWidth.doubleValue(),
                            annotation.getLabel()
                    );
                })
                .toList();

        return new AnnotationSetResponse(
                entity.getId(),
                entity.getPictureId(),
                CoordinateSpace.valueOf(entity.getCoordinateSpace()),
                entity.getSourceWidth(),
                entity.getSourceHeight(),
                shapes,
                entity.getNote(),
                entity.getRevision(),
                entity.getUpdatedAt().toInstant()
        );
    }
}
