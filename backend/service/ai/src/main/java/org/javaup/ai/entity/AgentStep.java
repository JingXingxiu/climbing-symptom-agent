package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import org.javaup.ai.api.dto.AgentEnums.AgentStepStatus;
import org.javaup.ai.api.dto.AgentEnums.AgentStepType;
import org.javaup.ai.api.dto.AgentQuestion;
import org.postgresql.util.PGobject;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 有恢复、调试或解释价值的关键节点；parentStepId 连接历史分支。 */
@Getter
@Setter
@TableName("agent_step")
public class AgentStep {

    /** 关键节点 ID。 */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 节点所属的分析任务。 */
    private UUID sessionId;

    /** Session 内单调分配的序号，不等于当前分支的深度。 */
    private Integer stepNo;

    /** 当前节点基于哪个旧节点继续；修改历史回答时连接原回答之前的节点。 */
    private UUID parentStepId;

    /** 与 parentStepId 配对的父序号，数据库据此限制父节点编号更小以防止成环。 */
    private Integer parentStepNo;

    /** 创建节点时的执行代次，用于识别已失效的异步执行。 */
    private Long executionGeneration;

    /** USER_INPUT、NARROW_DOWN 等关键步骤类型。 */
    private String stepType;

    /** STARTED/WAITING/COMPLETED/FAILED/DISCARDED。 */
    private String status;

    /** 用户回答等写操作的客户端幂等键，模型自主步骤可为空。 */
    private UUID clientOperationId;

    /** 操作起点、问题和回答等内容的规范化摘要，拒绝同键不同请求。 */
    private String payloadFingerprint;

    /** 面向业务的问题标识，不用它单独判断“是否同一轮回答”。 */
    private String questionKey;

    /** 此步骤生成的问题结构，例如题干、选项与回答形式。 */
    @TableField("question")
    private PGobject question;

    /** 用户提交的结构化回答；不是模型隐藏推理。 */
    @TableField("answer")
    private PGobject answer;

    /** 本节点用到的输入、图片及标注等引用。 */
    @TableField("input_references")
    private PGobject inputReferences;

    /** 由输入提取的可解释症状事实。 */
    @TableField("extracted_facts")
    private PGobject extractedFacts;

    /** 本轮公开业务决策，例如继续提问或请求确认，不保存思维链。 */
    @TableField("decision")
    private PGobject decision;

    /** 本轮识别的矛盾代码数组。 */
    @TableField("contradiction_codes")
    private PGobject contradictionCodes;

    /** 用户可以看到的问题、澄清说明或结果摘要。 */
    private String userVisibleMessage;

    /** 节点开始处理时间。 */
    private OffsetDateTime startedAt;

    /** 节点形成稳定处理结果的时间。 */
    private OffsetDateTime completedAt;

    /** 构造步骤的关联信息；步骤序号由 Service 预先分配。 */
    public static AgentStep create(
            AgentSession session,
            AgentStep parent,
            AgentStepType type,
            OffsetDateTime startedAt
    ) {
        AgentStep step = new AgentStep();
        step.setId(UUID.randomUUID());
        step.setSessionId(session.getId());
        step.setStepNo(session.getLastStepNo());
        step.setParentStepId(parent == null ? null : parent.getId());
        step.setParentStepNo(parent == null ? null : parent.getStepNo());
        step.setExecutionGeneration(session.getExecutionGeneration());
        step.setStepType(type.name());
        step.setStartedAt(startedAt);

        return step;
    }

    /** 保存本轮已接受的公开业务决策，不包含模型隐藏推理。 */
    public void recordDecision(
            String message,
            PGobject extractedFacts,
            PGobject contradictionCodes,
            PGobject decision
    ) {
        setUserVisibleMessage(message);
        setExtractedFacts(extractedFacts);
        setContradictionCodes(contradictionCodes);
        setDecision(decision);
    }

    /** 保存用户回答及其幂等信息，不修改所属 Session。 */
    public void recordAnswer(
            AgentQuestion question,
            UUID operationId,
            String fingerprint,
            PGobject answer,
            OffsetDateTime completedAt
    ) {
        setStatus(AgentStepStatus.COMPLETED.name());
        setClientOperationId(operationId);
        setPayloadFingerprint(fingerprint);
        setQuestionKey(question.questionKey());
        setAnswer(answer);
        setUserVisibleMessage("已保存：" + question.prompt());
        setCompletedAt(completedAt);
    }

    public void awaitAnswer(
            AgentQuestion question,
            PGobject questionBody,
            OffsetDateTime completedAt
    ) {
        setQuestion(questionBody);
        setQuestionKey(question.questionKey());
        setStatus(AgentStepStatus.WAITING.name());
        setCompletedAt(completedAt);
    }

    public void complete(OffsetDateTime completedAt) {
        setStatus(AgentStepStatus.COMPLETED.name());
        setCompletedAt(completedAt);
    }
}
