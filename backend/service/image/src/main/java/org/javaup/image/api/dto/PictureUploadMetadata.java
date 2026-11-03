package org.javaup.image.api.dto;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * JSON part accompanying a multipart picture upload.
 */
public record PictureUploadMetadata(
        @NotNull VisualSourceType sourceType,
        @NotNull UUID clientRequestId,
        UUID assessmentId,
        @Valid ViewState3D viewState,
        @Size(max = 500) String userNote
) {
}
