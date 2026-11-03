package org.javaup.ai.model;

import java.util.List;

import org.javaup.ai.api.dto.AgentEnums.AgentBehavior;
import org.javaup.ai.api.dto.AgentEnums.RecommendationLevel;
import org.javaup.ai.api.dto.AgentQuestion;
import tools.jackson.databind.JsonNode;

/** 一次模型调用形成的可验证输出，供应用层决定是否接受。 */
public record AgentDecision(
        AgentBehavior behavior,
        String message,
        AgentQuestion question,
        JsonNode extractedFacts,
        List<String> contradictionCodes,
        OutcomeDraft outcome,
        List<Reference> references
) {

    public record OutcomeDraft(
            RecommendationLevel recommendationLevel,
            String summary,
            List<String> reasons,
            List<String> suggestedNextActions,
            List<String> warningSigns,
            String uncertaintyNote
    ) {}

    public record Reference(
            String title,
            String url,
            String excerpt
    ) {}
}
