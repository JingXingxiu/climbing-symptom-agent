package org.javaup.ai.mapper;

import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.javaup.ai.entity.AgentSession;

public interface AgentSessionMapper extends BaseMapper<AgentSession> {

    @Select("SELECT * FROM agent_session WHERE id=#{sessionId} AND user_id=#{userId} FOR UPDATE")
    AgentSession selectOwnedForUpdate(
            @Param("userId") UUID userId,
            @Param("sessionId") UUID sessionId
    );

    @Select(
            "SELECT * FROM agent_session WHERE user_id=#{userId} AND client_request_id=#{requestId}")
    AgentSession selectByRequest(
            @Param("userId") UUID userId,
            @Param("requestId") UUID requestId
    );

    @Select(
            """
        SELECT * FROM agent_session WHERE user_id=#{userId} AND assessment_id=#{assessmentId}
        AND status IN ('CREATED','RUNNING','WAITING_FOR_USER','PAUSED','FAILED')
        """)
    AgentSession selectOpen(
            @Param("userId") UUID userId,
            @Param("assessmentId") UUID assessmentId
    );

    @Select(
            "SELECT COALESCE(MAX(attempt_no),0)+1 FROM agent_session WHERE assessment_id=#{assessmentId}")
    int nextAttempt(@Param("assessmentId") UUID assessmentId);

    /** 唯一约束冲突返回0，不让PostgreSQL事务进入异常中止状态。 */
    @Insert(
            """
        INSERT INTO agent_session (id,assessment_id,user_id,assessment_version,attempt_no,client_request_id,request_fingerprint,status,workflow_version,preferred_locale,model_provider,model_name,input_snapshot_schema_version,input_snapshot,source_session_id,source_checkpoint_id,active_checkpoint_id,last_step_no,version,execution_generation,execution_deadline_at,total_model_calls,max_model_calls,max_steps,termination_reason_code,termination_detail,started_at,completed_at,created_at,updated_at)
        VALUES (#{id},#{assessmentId},#{userId},#{assessmentVersion},#{attemptNo},#{clientRequestId},#{requestFingerprint},#{status},#{workflowVersion},#{preferredLocale},#{modelProvider},#{modelName},#{inputSnapshotSchemaVersion},#{inputSnapshot},#{sourceSessionId},#{sourceCheckpointId},#{activeCheckpointId},#{lastStepNo},#{version},#{executionGeneration},#{executionDeadlineAt},#{totalModelCalls},#{maxModelCalls},#{maxSteps},#{terminationReasonCode},#{terminationDetail},#{startedAt},#{completedAt},#{createdAt},#{updatedAt})
        ON CONFLICT DO NOTHING
        """)
    int insertIfAbsent(AgentSession session);

    /** 显式写NULL以清理deadline/结束信息，避免MP默认忽略null留下旧状态。 */
    @Update(
            """
        UPDATE agent_session SET
          status=#{status}, active_checkpoint_id=#{activeCheckpointId}, last_step_no=#{lastStepNo},
          version=#{version}, execution_generation=#{executionGeneration},
          execution_deadline_at=#{executionDeadlineAt}, total_model_calls=#{totalModelCalls},
          model_provider=#{modelProvider},model_name=#{modelName},
          termination_reason_code=#{terminationReasonCode},termination_detail=#{terminationDetail},
          started_at=#{startedAt},completed_at=#{completedAt},updated_at=#{updatedAt}
        WHERE id=#{id} AND user_id=#{userId}
        """)
    int updateState(AgentSession session);
}
