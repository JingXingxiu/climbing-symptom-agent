package org.javaup.history.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import org.postgresql.util.PGobject;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 已定稿结果的历史版本。对应 V003，迁移需明确启用后执行。
 * 每次重新分析追加一条记录；旧版本不修改，assessment_result 仍供现有接口读取当前结果。
 */
@Getter
@Setter
@TableName("assessment_result_revision")
public class AssessmentResultRevision {

    /** 本条结果版本的独立 ID，不再用 assessmentId 作为主键。 */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属症状评估；外键只关联本服务的 assessment 表。 */
    private UUID assessmentId;

    /** 同一次 Assessment 的结果版本号，从 1 开始递增，不是乐观锁 version。 */
    private Integer revisionNo;

    /** 产生本结果的 AI 分析任务 ID；跨库业务引用，不建立外键。 */
    private UUID agentSessionId;

    /** AI 任务在同一 Assessment 下的分析次数，防止较旧任务迟到覆盖新结果。 */
    private Integer agentAttemptNo;

    /** 产生本结果的 OUTPUT_RESULT 节点 ID；非 AI 结果可以为空。 */
    private UUID agentStepId;

    /** 产生本结果时的 Agent 状态快照 ID，用于追溯结果对应的输入分支。 */
    private UUID agentCheckpointId;

    /** 本次分析对应的稳定 History 输入摘要；幂等重放也比较此值。 */
    private String assessmentInputFingerprint;

    /** 建议寻求专业帮助的紧迫程度，与当前结果的枚举取值保持一致。 */
    private String recommendationLevel;

    /** 面向用户的症状信息摘要，不作为医学诊断。 */
    private String summary;

    /** 支撑建议的原因，JSONB 数组。 */
    @TableField("reasons")
    private PGobject reasons;

    /** 建议的后续行动，JSONB 数组。 */
    @TableField("suggested_next_actions")
    private PGobject suggestedNextActions;

    /** 需要用户留意的警示表现，JSONB 数组。 */
    @TableField("warning_signs")
    private PGobject warningSigns;

    /** 结果来源：MOCK、RULE 或 AI。 */
    private String generatedBy;

    /** 生成器的可读版本标识，例如规则版本或工作流版本。 */
    private String generatorReference;

    /** 结果随附的安全提示。 */
    private String safetyNotice;

    /** 本结果在业务上定稿的时间，重试写入不改变该时间。 */
    private OffsetDateTime finalizedAt;

    /** 本条历史版本首次写入数据库的时间。 */
    private OffsetDateTime createdAt;
}
