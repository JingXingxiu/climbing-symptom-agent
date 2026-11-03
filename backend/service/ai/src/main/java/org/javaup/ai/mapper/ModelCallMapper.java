package org.javaup.ai.mapper;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.javaup.ai.entity.ModelCall;

public interface ModelCallMapper extends BaseMapper<ModelCall> {

    @Update(
            "UPDATE model_call SET status='DISCARDED',completed_at=#{now} WHERE session_id=#{sessionId} AND execution_generation=#{generation} AND status='STARTED'")
    int discardInflight(
            @Param("sessionId") UUID sessionId,
            @Param("generation") long generation,
            @Param("now") OffsetDateTime now
    );
}
