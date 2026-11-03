package org.javaup.image.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Internal batch lookup for the pictures attached to one assessment. */
public record ResolveVisualReferencesRequest(
        @NotNull UUID assessmentId,
        @NotNull @Size(max = 12) List<@NotNull UUID> pictureIds
) {
}
