package org.javaup.ai.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import org.javaup.ai.api.dto.AgentEnums.ModelCallErrorCategory;
import org.javaup.ai.api.dto.AgentEnums.ModelCallPurpose;
import org.javaup.ai.api.dto.AgentEnums.ModelCallStatus;
import org.javaup.ai.model.AgentModelResult;
import org.postgresql.util.PGobject;

/** 模型调用的预算预占及适度元数据，不默认持久化完整 Prompt/Response。 */
@Getter
@Setter
@TableName("model_call")
public class ModelCall {

    /** 单次模型调用 ID。 */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 调用所属分析任务。 */
    private UUID sessionId;

    /** 调用关联的关键步骤，没有对应节点时可为空。 */
    private UUID stepId;

    /** 发起调用时所基于的检查点，返回时需确认它仍是当前执行起点。 */
    private UUID baseCheckpointId;

    /** Session 内单调调用序号，发请求前随预算一起预占。 */
    private Integer callNo;

    /** 发起调用时的执行代次，用于拒绝迟到的旧响应。 */
    private Long executionGeneration;

    /** 本次调用实际使用的平台。 */
    private String provider;

    /** 本次调用实际使用的模型。 */
    private String modelName;

    /** 信息提取、提出澄清问题等调用目的。 */
    private String purpose;

    /** STARTED/SUCCEEDED/FAILED/DISCARDED；DISCARDED 只表示旧响应未被采纳，仍可记录耗时和 token。 */
    private String status;

    /** 平台返回的请求标识，供排障关联。 */
    private String providerRequestId;

    /** 应用追踪标识。 */
    private String traceId;

    /** 本轮执行总耗时（图片解析、检索、下载、模型及校验），毫秒；纯模型耗时见 model_request span。 */
    private Integer latencyMs;

    /** 平台提供的输入 token 数，不支持统计时为空。 */
    private Integer inputTokens;

    /** 平台提供的输出 token 数，不支持统计时为空。 */
    private Integer outputTokens;

    /** 平台提供的总 token 数，不支持统计时为空。 */
    private Integer totalTokens;

    /** 超时、配额不足等可分类错误。 */
    private String errorCategory;

    /** 平台或应用的具体错误代码。 */
    private String errorCode;

    /** 规范化模型请求的摘要，用于关联请求，不含完整敏感 Prompt。 */
    private String requestFingerprint;

    /** 少量安全响应元数据，不默认放入原始回答或用户敏感内容。 */
    @TableField("response_metadata")
    private PGobject responseMetadata;

    /** 外部请求开始时间。 */
    private OffsetDateTime startedAt;

    /** 调用结束或被废弃的时间。 */
    private OffsetDateTime completedAt;

    /** 调用记录与预算预占建立时间。 */
    private OffsetDateTime createdAt;

    /** 根据已预占预算的 Session 构造调用记录，不修改 Session。 */
    public static ModelCall start(
            AgentSession session,
            UUID baseCheckpointId,
            String requestFingerprint,
            PGobject responseMetadata,
            OffsetDateTime startedAt,
            OffsetDateTime createdAt
    ) {
        ModelCall call = new ModelCall();
        call.setId(UUID.randomUUID());
        call.setSessionId(session.getId());
        call.setBaseCheckpointId(baseCheckpointId);
        call.setCallNo(session.getTotalModelCalls());
        call.setExecutionGeneration(session.getExecutionGeneration());

        call.setProvider(session.getModelProvider());
        call.setModelName(session.getModelName());
        call.setPurpose(ModelCallPurpose.NEXT_SYMPTOM_STEP.name());
        call.setStatus(ModelCallStatus.STARTED.name());

        call.setRequestFingerprint(requestFingerprint);
        call.setResponseMetadata(responseMetadata);
        call.setStartedAt(startedAt);
        call.setCreatedAt(createdAt);

        return call;
    }

    /** 保存返回统计；是否采纳业务结果由 Service 判断。 */
    public void recordResponse(
            AgentModelResult result,
            int latencyMs,
            OffsetDateTime completedAt
    ) {
        setCompletedAt(completedAt);
        setLatencyMs(latencyMs);
        setProviderRequestId(result.providerRequestId());
        setInputTokens(nonNegative(result.inputTokens()));
        setOutputTokens(nonNegative(result.outputTokens()));
        setTotalTokens(nonNegative(result.totalTokens()));
    }

    /** 记录失败信息；有效执行与过期执行的状态仍由 Service 区分。 */
    public void recordFailure(
            ModelCallErrorCategory category,
            String errorCode,
            int latencyMs,
            OffsetDateTime completedAt
    ) {
        setErrorCategory(category.name());
        setErrorCode(errorCode);
        setLatencyMs(latencyMs);
        setCompletedAt(completedAt);
    }

    public void markSucceeded(UUID stepId) {
        setStepId(stepId);
        setStatus(ModelCallStatus.SUCCEEDED.name());
    }

    private static Integer nonNegative(Integer count) {
        return count == null ? null : Math.max(0, count);
    }
}
