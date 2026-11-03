package org.javaup.ai.mapper;

import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.javaup.ai.entity.AgentCheckpoint;

public interface AgentCheckpointMapper extends BaseMapper<AgentCheckpoint> {

    @Select("SELECT * FROM agent_checkpoint WHERE session_id=#{sessionId} AND step_id IS NULL")
    AgentCheckpoint selectInitial(@Param("sessionId") UUID sessionId);

    @Select("SELECT * FROM agent_checkpoint WHERE session_id=#{sessionId} AND step_id=#{stepId}")
    AgentCheckpoint selectByStep(
            @Param("sessionId") UUID sessionId,
            @Param("stepId") UUID stepId
    );

    @Select(
            "SELECT COALESCE(MAX(checkpoint_no),0)+1 FROM agent_checkpoint WHERE session_id=#{sessionId}")
    int nextNumber(@Param("sessionId") UUID sessionId);
}
