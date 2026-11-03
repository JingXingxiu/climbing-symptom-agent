package org.javaup.image.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import org.javaup.image.api.dto.CoordinateSpace;
import org.javaup.image.api.dto.ReplaceAnnotationSetRequest;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@TableName("annotation_set")
public class AnnotationSet {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID pictureId;

    private UUID assessmentId;

    private UUID ownerUserId;

    private String status;

    private String coordinateSpace;

    private Integer schemaVersion;

    private Integer revision;

    private String note;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    private Integer sourceWidth;

    private Integer sourceHeight;

    public static AnnotationSet create(
            UUID currentUserId,
            UUID pictureId,
            ReplaceAnnotationSetRequest request,
            OffsetDateTime now
    ) {
        AnnotationSet entity = new AnnotationSet();
        entity.id = UUID.randomUUID();
        entity.pictureId = pictureId;
        entity.assessmentId = request.assessmentId();
        entity.ownerUserId = currentUserId;
        entity.status = "DRAFT";
        entity.coordinateSpace = CoordinateSpace.NORMALIZED_0_1.name();
        entity.schemaVersion = 1;
        entity.createdAt = now;
        entity.applyContent(request, 1, now);
        return entity;
    }

    public void applyContent(
            ReplaceAnnotationSetRequest request,
            int nextRevision,
            OffsetDateTime now
    ) {
        this.sourceWidth = request.sourceWidth();
        this.sourceHeight = request.sourceHeight();
        this.note = request.note();
        this.revision = nextRevision;
        this.updatedAt = now;
    }
}
