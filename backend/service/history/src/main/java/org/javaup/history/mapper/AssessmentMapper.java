package org.javaup.history.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.javaup.history.entity.Assessment;

public interface AssessmentMapper extends BaseMapper<Assessment> {
    @Insert("""
        INSERT INTO assessment (
            id,
            user_id,
            create_idempotency_key,
            status,
            current_step_key,
            hand_side,
            onset_kind,
            onset_amount,
            onset_date,
            onset_text,
            trigger_context,
            normal_use_impact,
            additional_note,
            version
        ) VALUES (
            #{id},
            #{userId},
            #{createIdempotencyKey},
            #{status},
            #{currentStepKey},
            #{handSide},
            #{onsetKind},
            #{onsetAmount},
            #{onsetDate},
            #{onsetText},
            #{triggerContext},
            #{normalUseImpact},
            #{additionalNote},
            #{version}
        )
        ON CONFLICT (user_id, create_idempotency_key)
        DO NOTHING
        """)
    int insertIfAbsent(Assessment assessment);
}
