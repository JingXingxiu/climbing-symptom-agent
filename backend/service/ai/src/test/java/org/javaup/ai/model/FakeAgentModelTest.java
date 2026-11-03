package org.javaup.ai.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import org.javaup.ai.api.dto.AgentEnums.AgentBehavior;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class FakeAgentModelTest {

    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final FakeAgentModel fake = new FakeAgentModel(mapper);

    @Test
    void offlineFixtureProducesExplicitDemoQuestion() {
        AgentModelResult result = fake.analyze(request(List.of()));

        assertEquals(AgentBehavior.NARROW_DOWN, result.decision().behavior());
        assertTrue(result.decision().message().contains("离线演示"));
        assertNotNull(result.decision().question().questionId());
        assertNull(result.inputTokens());
        assertTrue(result.decision().references().isEmpty());
    }

    @Test
    void freeTextUncertaintyTriggersClarificationAndBoundedAnswersFinish() {
        var uncertain = new AgentModelRequest.Turn(
                "USER_INPUT", "", mapper.readTree("\"不确定是否同一个位置\""));
        assertEquals(
                AgentBehavior.CLARIFY_CONTRADICTION,
                fake.analyze(request(List.of(uncertain))).decision().behavior());

        var answer = new AgentModelRequest.Turn(
                "USER_INPUT", "", mapper.readTree("\"握持时不适\""));
        AgentDecision finalDecision = fake.analyze(request(List.of(answer, answer))).decision();
        assertEquals(AgentBehavior.PRODUCE_RESULT, finalDecision.behavior());
        assertTrue(finalDecision.outcome().summary().contains("不代表风险评估"));
    }

    private AgentModelRequest request(List<AgentModelRequest.Turn> history) {
        return new AgentModelRequest(
                UUID.randomUUID(),
                "test",
                "offline-fixture",
                "zh-CN",
                mapper.createObjectNode(),
                mapper.createObjectNode(),
                history,
                List.of());
    }
}
