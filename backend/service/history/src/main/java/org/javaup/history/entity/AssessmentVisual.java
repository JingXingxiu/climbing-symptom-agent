package org.javaup.history.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import org.javaup.history.api.dto.HistoryEnums.VisualSourceType;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@TableName("assessment_visual")
public class AssessmentVisual {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID assessmentId;

    private UUID pictureId;

    private UUID annotationSetId;

    private String sourceType;

    private Integer sortOrder;

    private OffsetDateTime createdAt;

    public static AssessmentVisual create(
            UUID assessmentId,
            UUID pictureId,
            UUID annotationSetId,
            VisualSourceType sourceType,
            int sortOrder,
            OffsetDateTime now
    ) {
        AssessmentVisual entity = new AssessmentVisual();
        entity.id = UUID.randomUUID();
        entity.assessmentId = assessmentId;
        entity.pictureId = pictureId;
        entity.annotationSetId = annotationSetId;
        entity.sourceType = sourceType.name();
        entity.sortOrder = sortOrder;
        entity.createdAt = now;
        return entity;
    }
}
