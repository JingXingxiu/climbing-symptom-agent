package org.javaup.ai.mapper;

import java.util.List;
import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.javaup.ai.entity.AgentStep;

public interface AgentStepMapper extends BaseMapper<AgentStep> {

    @Select(
            "SELECT * FROM agent_step WHERE session_id=#{sessionId} AND client_operation_id=#{operationId}")
    AgentStep selectByOperation(
            @Param("sessionId") UUID sessionId,
            @Param("operationId") UUID operationId
    );

    @Select("SELECT * FROM agent_step WHERE session_id=#{sessionId} ORDER BY step_no")
    List<AgentStep> selectSessionSteps(@Param("sessionId") UUID sessionId);
}
