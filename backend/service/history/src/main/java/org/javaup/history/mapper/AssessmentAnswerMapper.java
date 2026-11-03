package org.javaup.history.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.javaup.history.entity.AssessmentAnswer;

public interface AssessmentAnswerMapper extends BaseMapper<AssessmentAnswer> {

    /** A replay of the same client answer returns zero without aborting the transaction. */
    @Insert("""
            INSERT INTO assessment_answer (
                id, assessment_id, client_answer_id, sequence_no,
                question_key, question_text, answer, answer_source, created_at
            ) VALUES (
                #{id,jdbcType=OTHER,typeHandler=org.javaup.history.persistence.typehandler.UuidTypeHandler},
                #{assessmentId,jdbcType=OTHER,typeHandler=org.javaup.history.persistence.typehandler.UuidTypeHandler},
                #{clientAnswerId,jdbcType=OTHER,typeHandler=org.javaup.history.persistence.typehandler.UuidTypeHandler},
                #{sequenceNo}, #{questionKey}, #{questionText},
                #{answer,jdbcType=OTHER,typeHandler=org.javaup.history.persistence.typehandler.PgObjectTypeHandler},
                #{answerSource}, #{createdAt}
            )
            ON CONFLICT (assessment_id, client_answer_id) DO NOTHING
            """)
    int insertIfAbsent(AssessmentAnswer answer);
}
