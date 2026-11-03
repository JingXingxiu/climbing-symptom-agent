package org.javaup.ai.api.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.javaup.ai.api.dto.AgentEnums.AgentAnswerType;

/**
 * A bounded, structured question rather than a free-form unlimited chat turn.
 */
public record AgentQuestion(
        @NotNull UUID questionId,
        @NotBlank String questionKey,
        @NotBlank String prompt,
        String helpText,
        @NotNull AgentAnswerType answerType,
        List<@Valid AgentQuestionOption> options,
        boolean required
) {

    public AgentQuestion withIdIfMissing() {
        if (questionId != null) {
            return this;
        }

        return new AgentQuestion(
                UUID.randomUUID(),
                questionKey,
                prompt,
                helpText,
                answerType,
                options,
                required);
    }
}
