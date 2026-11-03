package org.javaup.ai.model;

import static org.javaup.ai.api.problem.AIErrorCode.MODEL_REVISION_NOT_SUPPORTED;

import java.util.List;

import org.javaup.ai.api.problem.ApiException;
import org.javaup.ai.review.ReviewIssue;
import org.springframework.http.HttpStatus;

/** 可替换模型边界；每次 analyze 恰好一次模型请求，不在内部无限自动循环。 */
public interface AgentModelClient {

    void requireAvailable(
            String provider,
            String model
    );

    AgentModelResult analyze(AgentModelRequest request);

    /** One explicit correction request; unsupported adapters must fail clearly, never replay the rejected answer. */
    default AgentModelResult revise(
            AgentModelRequest request,
            AgentDecision candidate,
            List<ReviewIssue> issues
    ) {
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                MODEL_REVISION_NOT_SUPPORTED, "The selected model adapter does not support result correction.");
    }
}
