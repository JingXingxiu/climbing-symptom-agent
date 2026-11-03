package org.javaup.history.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record AssessmentPageResponse(
        @NotNull List<@Valid AssessmentSummary> items,
        String nextCursor,
        boolean hasMore
) {
}
