package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import org.javaup.ai.api.dto.AgentEnums.AgentSessionStatus;
import org.javaup.ai.api.dto.AgentEnums.AgentTerminationReason;
import org.javaup.ai.api.dto.CreateAgentSessionRequest;
import org.javaup.ai.config.AgentWorkflowProperties;
import org.postgresql.util.PGobject;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/** 一次有限步骤的分析任务；当前分支由 activeCheckpointId 唯一确定。启用前需显式应用AI SQL。 */
@Getter
@Setter
@TableName("agent_session")
public class AgentSession {

    private static final String WORKFLOW_NAME = "symptom-agent";

    /** 本次分析任务的业务 ID。 */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** History 所有的 Assessment ID，只有业务引用，没有跨库外键。 */
    private UUID assessmentId;

    /** 创建任务时采集的 Assessment 版本，仅用于输入来源追溯。 */
    private Integer assessmentVersion;

    /** 任务所属用户，由服务从可信用户上下文取得。 */
    private UUID userId;

    /** 同一 Assessment 的第几次分析；完成后重新分析会创建新 Session。 */
    private Integer attemptNo;

    /** 客户端创建任务的幂等键，同一用户重复创建时用于返回原任务。 */
    private UUID clientRequestId;

    /** 创建请求的规范化摘要，用于拒绝同幂等键但不同内容的请求。 */
    private String requestFingerprint;

    /** CREATED/RUNNING/WAITING_FOR_USER/PAUSED/COMPLETED/FAILED/CANCELLED。 */
    private String status;

    /** 决定问题、状态结构和规则解释方式的工作流版本。 */
    private String workflowVersion;

    /** 用户交互使用的语言，例如 zh-CN。 */
    private String preferredLocale;

    /** 后续调用所选模型平台的配置标识，不保存密钥。 */
    private String modelProvider;

    /** 后续调用使用的模型名称；历史调用仍保留各自实际模型。 */
    private String modelName;

    /** 创建时冻结的基础输入、位置、图片及标注快照，不依赖临时签名 URL。 */
    @TableField("input_snapshot")
    private PGobject inputSnapshot;

    /** inputSnapshot 的业务结构版本。 */
    private Integer inputSnapshotSchemaVersion;

    /** 重新分析所参考的旧任务；与 sourceCheckpointId 成对存在。 */
    private UUID sourceSessionId;

    /** 旧任务中作为本次分析来源的检查点，不会被本次分析覆盖。 */
    private UUID sourceCheckpointId;

    /** 唯一当前状态指针；Checkpoint 再关联当前步骤和可恢复快照。 */
    private UUID activeCheckpointId;

    /** 全 Session 已分配的最大步骤序号，包含旧分支，回溯后不减小。 */
    private Integer lastStepNo;

    /** Session 乐观版本；状态、分支等变更时递增，回溯不回退。 */
    private Long version;

    /** 执行代次；暂停、回溯等操作递增后，旧调用响应不能继续推进任务。 */
    private Long executionGeneration;

    /** RUNNING 执行的接管期限；进程退出或超时后，恢复流程可递增代次并接管。 */
    private OffsetDateTime executionDeadlineAt;

    /** 已预占的模型调用次数，失败和废弃调用也计入，不从快照回退。 */
    private Integer totalModelCalls;

    /** 整个任务最多允许调用模型的次数。 */
    private Integer maxModelCalls;

    /** 整个任务可分配的步骤上限，包含旧分支和失效步骤。 */
    private Integer maxSteps;

    /** 完成、失败或取消等结束原因的稳定业务代码。 */
    private String terminationReasonCode;

    /** 简短、可解释的结束原因，避免存入敏感原始模型日志。 */
    private String terminationDetail;

    /** 首次开始执行的时间。 */
    private OffsetDateTime startedAt;

    /** 任务结束的时间，不用于表示用户暂时关闭页面。 */
    private OffsetDateTime completedAt;

    /** 任务建立时间。 */
    private OffsetDateTime createdAt;

    /** 最近一次任务状态变更时间。 */
    private OffsetDateTime updatedAt;

    /** 构造新任务；输入来源、模型选择、分析序号和持久化由 Service 决定。 */
    public static AgentSession create(
            UUID userId,
            UUID clientRequestId,
            CreateAgentSessionRequest request,
            String requestFingerprint,
            int assessmentVersion,
            int attemptNo,
            PGobject inputSnapshot,
            String modelProvider,
            String modelName,
            AgentWorkflowProperties settings,
            OffsetDateTime createdAt
    ) {
        AgentSession session = new AgentSession();
        session.setId(UUID.randomUUID());
        session.setAssessmentId(request.assessmentId());
        session.setUserId(userId);
        session.setAssessmentVersion(assessmentVersion);
        session.setAttemptNo(attemptNo);

        session.setClientRequestId(clientRequestId);
        session.setRequestFingerprint(requestFingerprint);
        session.setStatus(AgentSessionStatus.CREATED.name());
        session.setWorkflowVersion(WORKFLOW_NAME);

        String preferredLocale = request.preferredLocale();
        if (preferredLocale == null || preferredLocale.isBlank()) {
            preferredLocale = settings.getLocale();
        }
        session.setPreferredLocale(preferredLocale);
        session.setModelProvider(modelProvider);
        session.setModelName(modelName);

        session.setInputSnapshotSchemaVersion(1);
        session.setInputSnapshot(inputSnapshot);
        session.setSourceSessionId(request.sourceSessionId());
        session.setSourceCheckpointId(request.sourceCheckpointId());

        session.setLastStepNo(0);
        session.setVersion(1L);
        session.setExecutionGeneration(0L);
        session.setTotalModelCalls(0);
        session.setMaxModelCalls(settings.getMaxModelCalls());
        session.setMaxSteps(settings.getMaxSteps());

        session.setCreatedAt(createdAt);
        session.setUpdatedAt(createdAt);

        return session;
    }

    /** 预占一次模型执行，保留原有快照和全任务累计计数。 */
    public void beginExecution(
            OffsetDateTime changedAt,
            int timeoutSeconds
    ) {
        status = AgentSessionStatus.RUNNING.name();
        executionDeadlineAt = changedAt.plusSeconds(timeoutSeconds);
        totalModelCalls++;

        if (startedAt == null) {
            startedAt = changedAt;
        }

        terminationReasonCode = null;
        terminationDetail = null;
        completedAt = null;
        touch(changedAt);
    }

    /** 接受稳定业务节点，切换当前快照并推进任务版本。 */
    public void acceptCheckpoint(
            UUID checkpointId,
            AgentSessionStatus nextStatus,
            OffsetDateTime changedAt
    ) {
        activeCheckpointId = checkpointId;
        status = nextStatus.name();
        executionDeadlineAt = null;

        if (nextStatus == AgentSessionStatus.COMPLETED) {
            completedAt = changedAt;
            terminationReasonCode = AgentTerminationReason.RESULT_READY.name();
            terminationDetail = "候选结果已生成，等待写入History定稿";
        } else {
            terminationReasonCode = null;
            terminationDetail = null;
            completedAt = null;
        }

        touch(changedAt);
    }

    /** 标记当前执行失败，保留恢复起点及已消耗预算。 */
    public void failExecution(
            String errorCode,
            OffsetDateTime changedAt
    ) {
        status = AgentSessionStatus.FAILED.name();
        executionDeadlineAt = null;
        terminationReasonCode = errorCode;
        terminationDetail = "本轮分析未完成，可从已保存状态重试";
        touch(changedAt);
    }

    /** 使旧执行代次失效；调用记录的数据库更新由 Service 完成。 */
    public void invalidateExecution() {
        executionGeneration++;
        executionDeadlineAt = null;
    }

    public void touch(OffsetDateTime changedAt) {
        version++;
        updatedAt = changedAt;
    }

    public boolean isTerminal() {
        return Set.of(
                AgentSessionStatus.COMPLETED.name(),
                AgentSessionStatus.CANCELLED.name()
        ).contains(status);
    }

    public boolean hasModelCallBudget() {
        return totalModelCalls < maxModelCalls;
    }

    public boolean hasStepBudget() {
        return lastStepNo < maxSteps;
    }
}
