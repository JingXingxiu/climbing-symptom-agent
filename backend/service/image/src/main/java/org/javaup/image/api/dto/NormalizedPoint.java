package org.javaup.image.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

/**
 * A point with the source image's top-left as (0,0) and bottom-right as (1,1).
 */
public record NormalizedPoint(
        @DecimalMin("0.0") @DecimalMax("1.0") double x,
        @DecimalMin("0.0") @DecimalMax("1.0") double y
) {
}
