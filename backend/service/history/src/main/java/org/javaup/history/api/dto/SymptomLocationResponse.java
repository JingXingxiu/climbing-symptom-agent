package org.javaup.history.api.dto;
import org.javaup.history.entity.SymptomLocation;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.javaup.history.api.dto.HistoryEnums.FingerCode;
import org.javaup.history.api.dto.HistoryEnums.HandSide;
import org.javaup.history.api.dto.HistoryEnums.JointCode;

/**
 * Stable coarse location vocabulary. It is intentionally independent of mesh
 * triangle IDs and can be refined by visual annotations.
 */
public record SymptomLocationResponse(
        @NotNull UUID locationId,
        @NotNull HandSide handSide,
        @NotBlank @Pattern(regexp = "^hand\\.(left|right)\\.[a-z0-9.-]+$") String regionId,
        @NotBlank String displayLabel,
        FingerCode fingerCode,
        JointCode jointCode,
        @Size(max = 500) String detailNote,
        @NotNull Instant selectedAt
) {
    public static SymptomLocationResponse fromEntity(
            SymptomLocation entity
    ) {
        return new SymptomLocationResponse(
                entity.getId(),
                HandSide.valueOf(entity.getHandSide()),
                entity.getRegionId(),
                entity.getRegionLabel(),
                entity.getFingerCode() == null
                        ? null
                        : FingerCode.valueOf(entity.getFingerCode()),
                entity.getJointCode() == null
                        ? null
                        : JointCode.valueOf(entity.getJointCode()),
                entity.getDetailNote(),
                entity.getCreatedAt().toInstant()
        );
    }
}
