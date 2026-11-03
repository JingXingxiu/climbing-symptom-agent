package org.javaup.image.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import org.javaup.image.api.dto.PictureProcessingStatus;
import org.javaup.image.api.dto.PictureUploadStatus;
import org.javaup.image.api.dto.VisualSourceType;
import org.postgresql.util.PGobject;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Getter
@Setter
@TableName("picture_asset")
public class PictureAsset {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID ownerUserId;

    private String sourceType;

    private String standardAssetKey;

    private String standardHandSide;

    private String standardView;

    private String bucketName;

    private String objectKey;

    private String originalFilename;

    private String contentType;

    private Long sizeBytes;

    private String sha256;

    private Integer widthPx;

    private Integer heightPx;

    private String storageStatus;

    private String processingStatus;

    private Integer processingGeneration;

    private String processingErrorCode;

    /** Raw PostgreSQL jsonb; its API shape is intentionally deferred. */
    @TableField("safe_metadata")
    private PGobject safeMetadata;

    /** image-service-owned camera/view state for a frozen 3D model snapshot. */
    @TableField("view_state")
    private PGobject viewState;

    private String uploadIdempotencyKey;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    private OffsetDateTime deletedAt;

    /** Creates the database reservation for a user upload or a 3D snapshot. */
    public static PictureAsset create(
            UUID ownerUserId,
            UUID idempotencyKey,
            VisualSourceType sourceType,
            String bucketName,
            String objectKey,
            String originalFilename,
            String contentType,
            long sizeBytes,
            PGobject viewState
    ) {
        Objects.requireNonNull(ownerUserId, "ownerUserId is required");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey is required");
        Objects.requireNonNull(sourceType, "sourceType is required");
        if (sourceType == VisualSourceType.STANDARD_REFERENCE) {
            throw new IllegalArgumentException("Standard pictures are not user uploads");
        }
        if (bucketName == null || bucketName.isBlank()
                || objectKey == null || objectKey.isBlank()
                || contentType == null || contentType.isBlank()
                || sizeBytes <= 0) {
            throw new IllegalArgumentException("A non-empty upload and its storage location are required");
        }

        OffsetDateTime now = OffsetDateTime.now();
        PictureAsset picture = new PictureAsset();
        picture.id = UUID.randomUUID();
        picture.ownerUserId = ownerUserId;
        picture.sourceType = sourceType.name();
        picture.bucketName = bucketName;
        picture.objectKey = objectKey;
        picture.originalFilename = originalFilename;
        picture.contentType = contentType;
        picture.sizeBytes = sizeBytes;
        picture.storageStatus = PictureUploadStatus.INITIATED.name();
        picture.processingStatus = PictureProcessingStatus.PENDING.name();
        picture.processingGeneration = 1;
        picture.uploadIdempotencyKey = idempotencyKey.toString();
        picture.viewState = viewState;
        picture.createdAt = now;
        picture.updatedAt = now;

        PGobject safeMetadata = new PGobject();
        safeMetadata.setType("jsonb");
        try {
            safeMetadata.setValue("{}");
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to initialize picture metadata", exception);
        }
        picture.safeMetadata = safeMetadata;
        return picture;
    }
}
