package org.javaup.image.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.javaup.image.entity.AnnotationSet;

public interface AnnotationSetMapper extends BaseMapper<AnnotationSet> {

    /** First creation claims this picture/user/Assessment scope, or returns zero. */
    @Insert("""
            INSERT INTO annotation_set (
                id, picture_id, assessment_id, owner_user_id,
                status, coordinate_space, schema_version, revision,
                source_width, source_height, note, created_at, updated_at
            ) VALUES (
                #{id,jdbcType=OTHER,typeHandler=org.javaup.image.persistence.typehandler.UuidTypeHandler},
                #{pictureId,jdbcType=OTHER,typeHandler=org.javaup.image.persistence.typehandler.UuidTypeHandler},
                #{assessmentId,jdbcType=OTHER,typeHandler=org.javaup.image.persistence.typehandler.UuidTypeHandler},
                #{ownerUserId,jdbcType=OTHER,typeHandler=org.javaup.image.persistence.typehandler.UuidTypeHandler},
                #{status}, #{coordinateSpace}, #{schemaVersion}, #{revision},
                #{sourceWidth}, #{sourceHeight}, #{note}, #{createdAt}, #{updatedAt}
            )
            ON CONFLICT (picture_id, owner_user_id, assessment_id) DO NOTHING
            """)
    int insertIfAbsent(AnnotationSet annotationSet);
}
