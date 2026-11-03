package org.javaup.ai.review;

import java.util.List;

/** Validated review output and actual provider usage, without raw Prompt/Response. */
public record ReviewResult(
        ReviewVerdict verdict,
        List<ReviewIssue> issues,
        String providerRequestId,
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens
) {
    public ReviewResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
