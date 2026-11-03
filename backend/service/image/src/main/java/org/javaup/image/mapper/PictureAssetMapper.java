package org.javaup.image.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.javaup.image.entity.PictureAsset;

public interface PictureAssetMapper extends BaseMapper<PictureAsset> {

    /** Claims an upload key before object storage is contacted; a duplicate returns zero. */
    @Insert("""
            INSERT INTO picture_asset (
                id, owner_user_id, source_type, bucket_name, object_key,
                original_filename, content_type, size_bytes, sha256, width_px, height_px,
                storage_status, processing_status, processing_generation,
                safe_metadata, view_state, upload_idempotency_key, created_at, updated_at
            ) VALUES (
                #{id,jdbcType=OTHER,typeHandler=org.javaup.image.persistence.typehandler.UuidTypeHandler},
                #{ownerUserId,jdbcType=OTHER,typeHandler=org.javaup.image.persistence.typehandler.UuidTypeHandler},
                #{sourceType}, #{bucketName}, #{objectKey},
                #{originalFilename}, #{contentType}, #{sizeBytes}, #{sha256}, #{widthPx}, #{heightPx},
                #{storageStatus}, #{processingStatus}, #{processingGeneration},
                #{safeMetadata,jdbcType=OTHER,typeHandler=org.javaup.image.persistence.typehandler.PgObjectTypeHandler},
                #{viewState,jdbcType=OTHER,typeHandler=org.javaup.image.persistence.typehandler.PgObjectTypeHandler},
                #{uploadIdempotencyKey}, #{createdAt}, #{updatedAt}
            )
            ON CONFLICT (owner_user_id, upload_idempotency_key)
                WHERE upload_idempotency_key IS NOT NULL DO NOTHING
            """)
    int insertIfAbsent(PictureAsset picture);
}
