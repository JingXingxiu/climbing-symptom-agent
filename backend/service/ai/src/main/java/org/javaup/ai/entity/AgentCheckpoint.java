package org.javaup.ai.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import org.postgresql.util.PGobject;

/** 一个稳定节点之后的完整业务状态；Session 通过指针选择当前 Checkpoint。 */
@Getter
@Setter
@TableName("agent_checkpoint")
public class AgentCheckpoint {

    /** 检查点 ID。 */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 检查点所属任务。 */
    private UUID sessionId;

    /** 形成此快照的步骤；尚无步骤的初始检查点为空。 */
    private UUID stepId;

    /** Session 内单调分配的检查点序号，不随回溯减少。 */
    private Integer checkpointNo;

    /** state 的结构版本，恢复时据此解读数据。 */
    private Integer stateSchemaVersion;

    /** 已确认事实、待解决矛盾及当前问题等完整状态；不恢复全局预算或执行代次。 */
    @TableField("state")
    private PGobject state;

    /** 状态快照的 SHA-256 摘要，用于检查内容一致性。 */
    private String stateChecksum;

    /** 快照保存时间。 */
    private OffsetDateTime createdAt;

    /** 构造一个稳定状态快照；检查点序号和状态摘要由 Service 提供。 */
    public static AgentCheckpoint create(
            UUID sessionId,
            UUID stepId,
            int checkpointNo,
            PGobject state,
            String stateChecksum,
            OffsetDateTime createdAt
    ) {
        AgentCheckpoint point = new AgentCheckpoint();
        point.setId(UUID.randomUUID());
        point.setSessionId(sessionId);
        point.setStepId(stepId);
        point.setCheckpointNo(checkpointNo);

        point.setStateSchemaVersion(1);
        point.setState(state);
        point.setStateChecksum(stateChecksum);
        point.setCreatedAt(createdAt);

        return point;
    }
}
