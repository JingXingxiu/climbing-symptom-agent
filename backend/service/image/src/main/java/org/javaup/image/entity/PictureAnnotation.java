package org.javaup.image.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import org.javaup.image.api.dto.AnnotationGeometry;
import org.javaup.image.api.dto.AnnotationShape;
import org.postgresql.util.PGobject;
import tools.jackson.databind.ObjectMapper;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@TableName("picture_annotation")
public class PictureAnnotation {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID annotationSetId;

    private String shapeType;

    /** Shape geometry, including its polymorphic kind, in normalized coordinates. */
    @TableField("geometry")
    private PGobject geometry;

    /** Optional color and normalized line width, separate from geometry. */
    @TableField("style")
    private PGobject style;

    private String label;

    private Integer sortOrder;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    public static PictureAnnotation create(
            UUID annotationSetId,
            AnnotationShape shape,
            int sortOrder,
            OffsetDateTime now,
            ObjectMapper objectMapper
    ) {
        PictureAnnotation entity = new PictureAnnotation();
        entity.id = shape.shapeId();
        entity.annotationSetId = annotationSetId;
        entity.shapeType = shape.type().name();
        entity.label = shape.label();
        entity.sortOrder = sortOrder;
        entity.createdAt = now;
        entity.updatedAt = now;

        Map<String, Object> style = new HashMap<>();
        if (shape.color() != null) {
            style.put("color", shape.color());
        }
        if (shape.lineWidthNormalized() != null) {
            style.put("lineWidthNormalized", shape.lineWidthNormalized());
        }

        PGobject geometryJson = new PGobject();
        geometryJson.setType("jsonb");
        PGobject styleJson = new PGobject();
        styleJson.setType("jsonb");

        try {
            geometryJson.setValue(objectMapper.writerFor(AnnotationGeometry.class)
                    .writeValueAsString(shape.geometry()));
            styleJson.setValue(objectMapper.writeValueAsString(style));
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to prepare annotation JSON", exception);
        }

        entity.geometry = geometryJson;
        entity.style = styleJson;
        return entity;
    }
}
