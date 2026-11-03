package org.javaup.ai.review;

/** Fixed categories keep review output useful for correction and aggregate observation. */
public enum ReviewIssueCode {
    INPUT_MISMATCH,
    UNRESOLVED_CONTRADICTION,
    UNSUPPORTED_CLAIM,
    UNSAFE_ADVICE,
    MISSING_UNCERTAINTY,
    VISUAL_MISMATCH,
    OTHER
}
