package org.javaup.ai.api.dto;

/** 有限步骤分析的 API 与持久化状态。 */
public final class AgentEnums {

    private AgentEnums() {}

    /** Agent 分析任务的状态。 */
    public enum AgentSessionStatus {
        /** 当前输入已保存，等待执行下一步模型分析。 */
        CREATED,
        /** 正在分析。 */
        RUNNING,
        /** 等待用户回答。 */
        WAITING_FOR_USER,
        /** 用户明确暂停，后续从已保存的检查点恢复。 */
        PAUSED,
        /** 已完成。 */
        COMPLETED,
        /** 执行失败。 */
        FAILED,
        /** 已取消。 */
        CANCELLED
    }

    /** Agent 本轮采取的行为。 */
    public enum AgentBehavior {
        /** 继续缩小症状位置或表现的范围。 */
        NARROW_DOWN,
        /** 澄清含糊或相互矛盾的描述。 */
        CLARIFY_CONTRADICTION,
        /** 生成最终分析结果。 */
        PRODUCE_RESULT
    }

    /** 动态问题允许的回答形式。 */
    public enum AgentAnswerType {
        /** 单选。 */
        SINGLE_CHOICE,
        /** 多选。 */
        MULTI_CHOICE,
        /** 是或否。 */
        BOOLEAN,
        /** 自由文字。 */
        TEXT
    }

    /** 用于恢复和解释任务的关键步骤类型。 */
    public enum AgentStepType {
        /** 接收用户输入。 */
        USER_INPUT,
        /** 提取关键症状信息。 */
        EXTRACT_INFORMATION,
        /** 进一步缩小症状范围。 */
        NARROW_DOWN,
        /** 发现描述之间的矛盾。 */
        DETECT_CONTRADICTION,
        /** 请求用户重新确认。 */
        REQUEST_CONFIRMATION,
        /** 输出最终结果。 */
        OUTPUT_RESULT
    }

    /** 关键步骤的持久化状态，不代表每一次内部函数调用。 */
    public enum AgentStepStatus {
        /** 步骤已建立，尚未提交稳定结果。 */
        STARTED,
        /** 已生成问题，等待用户输入；该步骤内容可作为稳定历史保留。 */
        WAITING,
        /** 步骤已完成。 */
        COMPLETED,
        /** 步骤执行失败。 */
        FAILED,
        /** 执行已失效，不能推进当前分支。 */
        DISCARDED
    }

    /** 模型调用的持久化状态，调用前先记录 STARTED 并占用预算。 */
    public enum ModelCallStatus {
        /** 已预占调用预算，等待外部模型返回。 */
        STARTED,
        /** 当前执行内获得合法输出；候选是否最终被采纳还要看 Step 和评审元数据。 */
        SUCCEEDED,
        /** 模型调用失败。 */
        FAILED,
        /** 旧响应未被当前执行采纳，不代表模型调用失败，仍保留耗时和 token 等元数据。 */
        DISCARDED
    }

    /** 正常结束任务的业务原因；执行失败时另存具体错误码。 */
    public enum AgentTerminationReason {
        /** 用户主动取消分析。 */
        USER_CANCELLED,
        /** 候选结果已生成，等待交付 History。 */
        RESULT_READY
    }

    /** 模型调用用于完成哪一类业务任务。 */
    public enum ModelCallPurpose {
        /** 推进下一步症状分析。 */
        NEXT_SYMPTOM_STEP,
        /** 评审最终候选结果；不等同于临床正确性确认。 */
        QUALITY_REVIEW,
        /** 根据评审意见进行一次有界纠正。 */
        RESULT_REVISION
    }

    /** 模型执行失败的粗粒度分类，具体原因保存在错误码中。 */
    public enum ModelCallErrorCategory {
        /** 已识别的供应商、输入或业务校验失败。 */
        PROVIDER_OR_INPUT,
        /** 未分类的传输、解析或模型输出失败。 */
        MODEL_TRANSPORT_OR_OUTPUT
    }

    /** 结果由演示模型还是真实模型生成。 */
    public enum ResultGeneratedBy {
        /** 明确标识的离线演示结果。 */
        MOCK,
        /** 已配置的真实模型生成的结果。 */
        AI
    }

    /** 建议寻求专业帮助的紧迫程度。 */
    public enum RecommendationLevel {
        /** 可以先自行观察。 */
        SELF_MONITOR,
        /** 建议常规预约专业评估。 */
        ROUTINE_PROFESSIONAL,
        /** 建议尽快寻求专业评估。 */
        PROMPT_PROFESSIONAL,
        /** 建议立即寻求紧急帮助。 */
        URGENT
    }
}
