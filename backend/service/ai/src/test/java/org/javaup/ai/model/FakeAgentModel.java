package org.javaup.ai.model;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.javaup.ai.api.dto.AgentEnums.AgentAnswerType;
import org.javaup.ai.api.dto.AgentEnums.AgentBehavior;
import org.javaup.ai.api.dto.AgentEnums.AgentStepType;
import org.javaup.ai.api.dto.AgentEnums.RecommendationLevel;
import org.javaup.ai.api.dto.AgentQuestion;
import tools.jackson.databind.ObjectMapper;

/** 仅供单元测试的离线模型，用于验证流程，不给出医学判断。 */
public class FakeAgentModel {

    /** 离线演示模型用于触发重新确认流程的矛盾码。 */
    private static final String DEMO_DESCRIPTION_NEEDS_CONFIRMATION =
            "DEMO_DESCRIPTION_NEEDS_CONFIRMATION";

    private final ObjectMapper mapper;

    public FakeAgentModel(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public AgentModelResult analyze(AgentModelRequest request) {
        List<AgentModelRequest.Turn> answers =
                request.history().stream()
                        .filter(turn -> AgentStepType.USER_INPUT.name().equals(turn.type()))
                        .toList();
        String last = answers.isEmpty()
                ? ""
                : answers.getLast().answer().toString().toLowerCase(Locale.ROOT);

        boolean conflict = last.contains("矛盾") || last.contains("不确定") || last.contains("conflict");
        AgentDecision decision;

        if (conflict && answers.size() < 3) {
            decision = question(
                    AgentBehavior.CLARIFY_CONTRADICTION,
                    "demo.confirm_location",
                    "【离线演示】这段描述需要重新确认：请说明不适的位置，以及与前次描述不同的地方。",
                    List.of(DEMO_DESCRIPTION_NEEDS_CONFIRMATION));
        } else if (answers.size() < 2) {
            decision = question(
                    AgentBehavior.NARROW_DOWN,
                    answers.isEmpty() ? "demo.trigger" : "demo.normal_use",
                    answers.isEmpty()
                            ? "【离线演示】请补充哪个动作最容易出现不适？"
                            : "【离线演示】请补充不适对正常使用的影响？",
                    List.of());
        } else {
            decision = new AgentDecision(
                    AgentBehavior.PRODUCE_RESULT,
                    "【离线演示】已完成有限步骤的信息整理。",
                    null,
                    mapper.createObjectNode(),
                    List.of(),
                    new AgentDecision.OutcomeDraft(
                            RecommendationLevel.ROUTINE_PROFESSIONAL,
                            "【离线演示结果】已整理本次输入及补充回答。此输出仅用于验证流程，不代表风险评估或医学判断。",
                            List.of("离线 Fake 模型未调用任何真实模型，也未联网检索。"),
                            List.of("需要实际症状分析时，请切换已配置的真实模型；是否就医请咨询专业人员。"),
                            List.of(),
                            "Fake 结果不能用于健康决策。"),
                    List.of());
        }

        return new AgentModelResult(decision, "fake-" + UUID.randomUUID(), null, null, null);
    }

    private AgentDecision question(
            AgentBehavior behavior,
            String key,
            String prompt,
            List<String> contradictions
    ) {
        return new AgentDecision(
                behavior,
                prompt,
                new AgentQuestion(
                        UUID.randomUUID(),
                        key,
                        prompt,
                        null,
                        AgentAnswerType.TEXT,
                        List.of(),
                        true),
                mapper.createObjectNode(),
                contradictions,
                null,
                List.of());
    }
}
