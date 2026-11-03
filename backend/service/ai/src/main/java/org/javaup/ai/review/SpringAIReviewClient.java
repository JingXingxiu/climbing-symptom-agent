package org.javaup.ai.review;

import static org.javaup.ai.api.problem.AIErrorCode.QUALITY_REVIEW_NOT_CONFIGURED;

import java.util.Objects;

import org.javaup.ai.api.problem.ApiException;
import org.javaup.ai.model.AgentDecision;
import org.javaup.ai.model.AgentModelRequest;
import org.javaup.ai.model.ConfiguredAgentModelClient;
import org.javaup.ai.model.SpringAICompatibleModel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Uses a distinct server-allowlisted model profile; disabled review never becomes implicit approval. */
@Component
public class SpringAIReviewClient implements ReviewClient {
    private final ReviewProperties properties;
    private final ConfiguredAgentModelClient profiles;
    private final SpringAICompatibleModel model;

    public SpringAIReviewClient(
            ReviewProperties properties,
            ConfiguredAgentModelClient profiles,
            SpringAICompatibleModel model
    ) {
        this.properties = properties;
        this.profiles = profiles;
        this.model = model;
    }

    @Override
    public void requireAvailable(String primaryProvider, String primaryModel) {
        if (!properties.isEnabled()) {
            throw unavailable("Quality review is disabled.");
        }
        if (properties.getProvider() == null || properties.getProvider().isBlank()
                || properties.getModel() == null || properties.getModel().isBlank()) {
            throw unavailable("Quality review requires a server-configured model profile.");
        }
        if (Objects.equals(primaryProvider, properties.getProvider())
                && Objects.equals(primaryModel, properties.getModel())) {
            throw unavailable("The reviewer must use a different provider/model pair from the primary model.");
        }
        try {
            profiles.configuredProfile(properties.getProvider(), properties.getModel());
        } catch (ApiException invalid) {
            throw unavailable("The configured review profile is unavailable.");
        }
    }

    @Override
    public ReviewResult review(AgentModelRequest request, AgentDecision candidate) {
        requireAvailable(request.provider(), request.model());
        return model.review(
                request,
                candidate,
                profiles.configuredProfile(properties.getProvider(), properties.getModel()));
    }

    private static ApiException unavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, QUALITY_REVIEW_NOT_CONFIGURED, message);
    }
}
