package org.javaup.ai.model;

import static org.javaup.ai.api.problem.AIErrorCode.*;

import java.util.Objects;

import org.javaup.ai.api.problem.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 按服务端配置的白名单调用兼容协议的真实模型。 */
@Component
public class ConfiguredAgentModelClient implements AgentModelClient {

    private final AgentModelProperties properties;
    private final SpringAICompatibleModel real;

    public ConfiguredAgentModelClient(
            AgentModelProperties properties,
            SpringAICompatibleModel real
    ) {
        this.properties = properties;
        this.real = real;
    }

    @Override
    public void requireAvailable(
            String provider,
            String model
    ) {
        AgentModelProperties.Profile profile = find(provider, model);

        if (profile.getApiKey() == null
                || profile.getApiKey().isBlank()
                || profile.getBaseUrl() == null
                || profile.getBaseUrl().isBlank()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    MODEL_NOT_CONFIGURED,
                    "Selected model has no configured endpoint or credentials.");
        }
    }

    @Override
    public AgentModelResult analyze(AgentModelRequest request) {
        requireAvailable(request.provider(), request.model());

        return real.analyze(request, find(request.provider(), request.model()));
    }

    private AgentModelProperties.Profile find(
            String provider,
            String model
    ) {
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    MODEL_SELECTION_REQUIRED,
                    "Provider and model are required.");
        }

        return properties.getProfiles().values().stream()
                .filter(profile -> Objects.equals(profile.getProvider(), provider)
                        && Objects.equals(profile.getModel(), model))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.BAD_REQUEST,
                        MODEL_NOT_ALLOWED,
                        "Select a model from the server-configured allowlist."));
    }
}
