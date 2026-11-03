package org.javaup.ai.model;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.javaup.ai.api.problem.ApiException;
import org.javaup.ai.search.SearchProperties;
import org.javaup.ai.search.SearchTool;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.List;
import java.util.UUID;

class ConfiguredAgentModelClientTest {
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final AgentModelProperties properties = new AgentModelProperties();
    private final SearchTool search = new SearchTool(new SearchProperties(), mapper);
    private final SpringAICompatibleModel real = mock(SpringAICompatibleModel.class);
    private final ConfiguredAgentModelClient client =
            new ConfiguredAgentModelClient(properties, real);

    @Test
    void configuredModelUsesRealClient() {
        AgentModelProperties.Profile profile = new AgentModelProperties.Profile(
                "zhipu", "glm-4.6v-flash", "https://open.bigmodel.cn/api/paas/v4", "test");
        properties.getProfiles().put("zhipu", profile);
        AgentModelRequest request = request("zhipu", "glm-4.6v-flash");
        AgentModelResult result = new AgentModelResult(null, "request-id", 10, 5, 15);
        when(real.analyze(request, profile)).thenReturn(result);

        assertSame(result, client.analyze(request));
        verify(real).analyze(request, profile);
    }

    @Test
    void fakeIsNotAvailableByDefault() {
        ApiException failure = assertThrows(
                ApiException.class, () -> client.analyze(request("fake", "fake")));

        assertEquals("MODEL_NOT_ALLOWED", failure.code());
        verifyNoInteractions(real);
    }

    @Test
    void fakeNameDoesNotBypassCredentialChecks() {
        properties.getProfiles().put(
                "fake", new AgentModelProperties.Profile("fake", "fake", null, null));

        ApiException failure = assertThrows(
                ApiException.class, () -> client.analyze(request("fake", "fake")));

        assertEquals("MODEL_NOT_CONFIGURED", failure.code());
        verifyNoInteractions(real);
    }

    @Test
    void missingRealCredentialsFailsInsteadOfFallingBack() {
        properties
                .getProfiles()
                .put("qwen", new AgentModelProperties.Profile("qwen", "qwen3.7-flash", null, null));
        ApiException failure =
                assertThrows(
                        ApiException.class, () -> client.requireAvailable("qwen", "qwen3.7-flash"));
        assertEquals(503, failure.status().value());
        assertEquals("MODEL_NOT_CONFIGURED", failure.code());
        verifyNoInteractions(real);
    }

    @Test
    void realModelFailureIsPropagated() {
        AgentModelProperties.Profile profile = new AgentModelProperties.Profile(
                "zhipu", "glm-4.6v-flash", "https://open.bigmodel.cn/api/paas/v4", "test");
        properties.getProfiles().put("zhipu", profile);
        AgentModelRequest request = request("zhipu", "glm-4.6v-flash");
        ApiException failure = new ApiException(
                HttpStatus.BAD_GATEWAY, "MODEL_CALL_FAILED", "test");
        when(real.analyze(request, profile)).thenThrow(failure);

        assertSame(failure, assertThrows(ApiException.class, () -> client.analyze(request)));
    }

    @Test
    void unconfiguredModelIsRejected() {
        assertEquals(
                "MODEL_NOT_ALLOWED",
                assertThrows(
                                ApiException.class,
                                () -> client.requireAvailable("qwen", "arbitrary-model"))
                        .code());
    }

    @Test
    void disallowedImageHostIsNeverFetched() {
        AgentModelProperties.Profile profile =
                new AgentModelProperties.Profile("qwen", "model", "http://127.0.0.1:1/v1", "test");
        var request =
                new AgentModelRequest(
                        UUID.randomUUID(),
                        "qwen",
                        "model",
                        "zh-CN",
                        mapper.createObjectNode(),
                        mapper.createObjectNode(),
                        List.of(),
                        List.of(
                                new AgentModelRequest.Picture(
                                        UUID.randomUUID(),
                                        URI.create("http://unconfigured.invalid/image"),
                                        "image/png")));
        ApiException failure =
                assertThrows(
                        ApiException.class,
                        () ->
                                new SpringAICompatibleModel(properties, mapper, search)
                                        .analyze(request, profile));
        assertEquals("MODEL_IMAGE_HOST_NOT_ALLOWED", failure.code());
    }

    private AgentModelRequest request(String provider, String model) {
        return new AgentModelRequest(
                UUID.randomUUID(),
                provider,
                model,
                "zh-CN",
                mapper.createObjectNode(),
                mapper.createObjectNode(),
                List.of(),
                List.of());
    }
}
