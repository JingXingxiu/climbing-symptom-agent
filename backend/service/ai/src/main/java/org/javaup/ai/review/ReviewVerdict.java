package org.javaup.ai.review;

/** A review is a bounded quality decision, not a guarantee of medical correctness. */
public enum ReviewVerdict {
    ACCEPT,
    REVISE,
    ABSTAIN
}
