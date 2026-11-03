package org.javaup.ai.review;

/** A short public review reason; hidden reasoning and executable instructions are excluded. */
public record ReviewIssue(
        ReviewIssueCode code,
        String reason
) {}
