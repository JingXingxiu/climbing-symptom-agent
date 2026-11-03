package org.javaup.ai.review;

import org.javaup.ai.model.AgentDecision;
import org.javaup.ai.model.AgentModelRequest;

/** Each review call makes exactly one model request; retries and budgets belong to the workflow. */
public interface ReviewClient {
    void requireAvailable(String primaryProvider, String primaryModel);

    ReviewResult review(AgentModelRequest request, AgentDecision candidate);
}
