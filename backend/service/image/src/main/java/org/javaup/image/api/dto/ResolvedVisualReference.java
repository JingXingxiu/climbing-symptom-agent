package org.javaup.image.api.dto;

import java.util.UUID;

/** Minimal metadata needed by history-service; no object URLs or annotation shapes. */
public record ResolvedVisualReference(
        UUID pictureId,
        VisualSourceType sourceType,
        UUID annotationSetId
) {
}
