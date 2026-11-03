package org.javaup.image.api.dto;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * image-service-owned state needed to reproduce a frozen 3D hand-model view.
 * Mesh/raycast diagnostic data stays optional and is not medical semantics.
 */
public record ViewState3D(
        @NotBlank @Size(max = 80) String modelAssetVersion,
        @NotNull @Valid Vector3 camera,
        @NotNull @Valid Vector3 target,
        @Valid Vector3 up,
        @NotNull @Positive Double zoom,
        @Size(max = 100) String coarseRegionId,
        Map<String, Object> implementationHit
) {
}
