package org.javaup.image.api.dto;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.javaup.image.entity.PictureAsset;

/**
 * image-service-owned picture metadata. Signed URLs are delivery details, not
 * persistent cross-service identifiers.
 */
public record PictureResponse(
        @NotNull UUID pictureId,
        UUID ownerUserId,
        @NotNull VisualSourceType sourceType,
        String standardAssetKey,
        HandSide standardHandSide,
        StandardPictureView standardView,
        String originalFilename,
        @NotBlank String mediaType,
        @Positive long byteSize,
        @Positive Integer width,
        @Positive Integer height,
        @NotNull PictureUploadStatus uploadStatus,
        @NotNull PictureProcessingStatus processingStatus,
        URI originalUrl,
        URI thumbnailUrl,
        @Valid ViewState3D viewState,
        @NotNull Instant createdAt,
        @NotNull Instant updatedAt
) {

    public static PictureResponse fromEntity(
            PictureAsset picture,
            URI originalUrl,
            URI thumbnailUrl,
            ViewState3D viewState
    ) {
        return new PictureResponse(
                picture.getId(),
                picture.getOwnerUserId(),
                VisualSourceType.valueOf(picture.getSourceType()),
                picture.getStandardAssetKey(),
                picture.getStandardHandSide() == null
                        ? null
                        : HandSide.valueOf(picture.getStandardHandSide()),
                picture.getStandardView() == null
                        ? null
                        : StandardPictureView.valueOf(picture.getStandardView()),
                picture.getOriginalFilename(),
                picture.getContentType(),
                picture.getSizeBytes(),
                picture.getWidthPx(),
                picture.getHeightPx(),
                PictureUploadStatus.valueOf(picture.getStorageStatus()),
                PictureProcessingStatus.valueOf(picture.getProcessingStatus()),
                originalUrl,
                thumbnailUrl,
                viewState,
                picture.getCreatedAt().toInstant(),
                picture.getUpdatedAt().toInstant()
        );
    }
}
