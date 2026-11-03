package org.javaup.history.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.javaup.history.entity.AssessmentResultRevision;

public interface AssessmentResultRevisionMapper extends BaseMapper<AssessmentResultRevision> {

    @Select("SELECT * FROM assessment_result_revision WHERE agent_session_id = #{sessionId}")
    AssessmentResultRevision findBySession(@Param("sessionId") UUID sessionId);

    @Select("SELECT COALESCE(MAX(revision_no), 0) FROM assessment_result_revision WHERE assessment_id = #{assessmentId}")
    int maxRevisionNo(@Param("assessmentId") UUID assessmentId);

    @Select("SELECT COALESCE(MAX(agent_attempt_no), 0) FROM assessment_result_revision WHERE assessment_id = #{assessmentId}")
    int maxAttemptNo(@Param("assessmentId") UUID assessmentId);
}
