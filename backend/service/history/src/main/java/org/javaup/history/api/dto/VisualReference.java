package org.javaup.history.api.dto;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.javaup.history.api.dto.HistoryEnums.VisualSourceType;
import org.javaup.history.entity.AssessmentVisual;

/**
 * history-service stores image-service IDs as business references only.
 */
public record VisualReference(
        @NotNull UUID pictureId,
        @NotNull VisualSourceType sourceType,
        UUID annotationSetId,
        @Min(0) int sortOrder
) {
    public static VisualReference fromEntity(AssessmentVisual entity) {
        return new VisualReference(
                entity.getPictureId(),
                VisualSourceType.valueOf(entity.getSourceType()),
                entity.getAnnotationSetId(),
                entity.getSortOrder()
        );
    }
}
