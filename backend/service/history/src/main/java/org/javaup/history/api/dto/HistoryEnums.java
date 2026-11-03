package org.javaup.history.api.dto;

/** history-service 自有的接口枚举。 */
public final class HistoryEnums {

    private HistoryEnums() {
    }

    /** 评估任务状态。 */
    public enum AssessmentStatus {
        /** 草稿，尚未开始采集。 */
        DRAFT,
        /** 正在采集症状信息。 */
        COLLECTING,
        /** 正在澄清模糊或矛盾信息。 */
        CLARIFYING,
        /** 评估已完成。 */
        COMPLETED,
        /** 评估已取消。 */
        CANCELLED;

        public boolean isTerminal() {
            return this == COMPLETED || this == CANCELLED;
        }
    }

    /** 不适所在的左手或右手。 */
    public enum HandSide {
        /** 左手。 */
        LEFT,
        /** 右手。 */
        RIGHT
    }

    /** 症状开始时间的表达方式。 */
    public enum OnsetKind {
        /** 今天开始。 */
        TODAY,
        /** 若干天前开始。 */
        DAYS_AGO,
        /** 若干周前开始。 */
        WEEKS_AGO,
        /** 若干月前开始。 */
        MONTHS_AGO,
        /** 指定具体日期。 */
        EXACT_DATE,
        /** 开始时间不确定。 */
        UNKNOWN
    }

    /** 症状表现，支持多选。 */
    public enum SymptomType {
        /** 痛。 */
        PAIN,
        /** 酸。 */
        ACHING,
        /** 胀感。 */
        DISTENSION,
        /** 麻木。 */
        NUMBNESS,
        /** 肿胀。 */
        SWELLING,
        /** 无力。 */
        WEAKNESS,
        /** 僵硬。 */
        STIFFNESS,
        /** 其他表现或不确定。 */
        OTHER_UNCERTAIN
    }

    /** 症状对正常用手的影响程度。 */
    public enum NormalUseImpact {
        /** 不影响正常使用。 */
        NONE,
        /** 轻度影响。 */
        MILD,
        /** 中度影响。 */
        MODERATE,
        /** 严重影响。 */
        SEVERE,
        /** 影响程度不确定。 */
        UNKNOWN
    }

    /** 手指编号，不包含手掌和手腕。 */
    public enum FingerCode {
        /** 拇指。 */
        THUMB,
        /** 食指。 */
        INDEX,
        /** 中指。 */
        MIDDLE,
        /** 无名指。 */
        RING,
        /** 小指。 */
        LITTLE
    }

    /** 指尖或关节定位代码。 */
    public enum JointCode {
        /** 指尖。 */
        TIP,
        /** 远端指间关节，靠近指尖。 */
        DIP,
        /** 近端指间关节，手指中段。 */
        PIP,
        /** 掌指关节，手指与手掌连接处。 */
        MCP,
        /** 腕掌关节，掌骨与腕骨连接处。 */
        CMC
    }

    /** 用于症状标注的图片来源。 */
    public enum VisualSourceType {
        /** 3D 手部模型固定视角截图。 */
        MODEL_SNAPSHOT,
        /** 系统提供的标准手部图片。 */
        STANDARD_REFERENCE,
        /** 用户自行上传的图片。 */
        USER_UPLOAD
    }

    /** 评估答案的来源。 */
    public enum AssessmentAnswerSource {
        /** 首次结构化表单。 */
        INITIAL_FORM,
        /** Mock Agent 演示问答。 */
        MOCK_AGENT,
        /** 真实 AI Agent 问答。 */
        AI_AGENT,
        /** 用户主动修正描述。 */
        USER_CORRECTION
    }

    /** 后续行动的建议级别。 */
    public enum RecommendationLevel {
        /** 自行观察症状变化。 */
        SELF_MONITOR,
        /** 常规安排专业咨询。 */
        ROUTINE_PROFESSIONAL,
        /** 尽快寻求专业帮助。 */
        PROMPT_PROFESSIONAL,
        /** 需要紧急寻求专业帮助。 */
        URGENT
    }

    /** 评估结果的生成方式。 */
    public enum ResultGeneratedBy {
        /** Mock 演示结果。 */
        MOCK,
        /** 预设规则生成。 */
        RULE,
        /** AI 分析生成。 */
        AI
    }

    /** 当前页面步骤，用于恢复评估进度。 */
    public enum CurrentStepKey {
        /** 填写基础症状信息。 */
        BASIC_INPUT,
        /** 通过 3D 手部选择粗略位置。 */
        LOCATION_3D,
        /** 在图片上标注具体位置。 */
        VISUAL_ANNOTATION,
        /** 进入 Agent 补充问答。 */
        AGENT,
        /** 查看最终结果。 */
        RESULT
    }

}
