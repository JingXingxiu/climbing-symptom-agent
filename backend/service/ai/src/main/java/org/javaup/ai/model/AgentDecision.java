package org.javaup.ai.model;

import java.util.List;
import java.util.UUID;

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
            String excerpt,
            String sourceType,
            UUID documentId,
            UUID chunkId,
            String contentVersion
    ) {
        /** 保留联网资料的简洁构造；知识库来源另带稳定的文档和片段 ID。 */
        public Reference(String title, String url, String excerpt) {
            this(title, url, excerpt, "WEB_SEARCH", null, null, null);
        }

        public Reference(
                String title,
                String url,
                String excerpt,
                String sourceType,
                UUID documentId,
                UUID chunkId
        ) {
            this(title, url, excerpt, sourceType, documentId, chunkId, null);
        }
    }
}
