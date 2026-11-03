package org.javaup.history.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FinalizeAgentResultRequest(
        @NotNull UUID sessionId,
        @NotNull UUID stepId,
        @NotNull UUID checkpointId,
        @Positive int attemptNo,
        @NotNull HistoryEnums.RecommendationLevel recommendationLevel,
        @NotBlank @Size(max = 2000) String summary,
        @NotNull @Size(max = 20) List<@NotBlank @Size(max = 500) String> reasons,
        @NotNull @Size(max = 20) List<@NotBlank @Size(max = 500) String> suggestedNextActions,
        @NotNull @Size(max = 20) List<@NotBlank @Size(max = 500) String> warningSigns,
        @NotBlank @Size(max = 1000) String safetyNotice,
        @NotNull Instant finalizedAt,
        @NotBlank @Size(max = 120) String workflowVersion,
        @NotNull HistoryEnums.ResultGeneratedBy generatedBy,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String assessmentInputFingerprint
) {}
