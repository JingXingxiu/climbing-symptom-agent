package org.javaup.image.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@TableName("picture_variant")
public class PictureVariant {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID pictureId;

    private String variantType;

    private Integer processingGeneration;

    private String bucketName;

    private String objectKey;

    private String contentType;

    private Long sizeBytes;

    private Integer widthPx;

    private Integer heightPx;

    private String sha256;

    private OffsetDateTime createdAt;
}
