package org.javaup.ai.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import org.javaup.ai.api.problem.ApiException;
import org.javaup.ai.model.AgentModelProperties;
import org.javaup.ai.model.ConfiguredAgentModelClient;
import org.javaup.ai.model.SpringAICompatibleModel;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SpringAIReviewClientTest {
    private final ReviewProperties properties = new ReviewProperties();
    private final ConfiguredAgentModelClient profiles = mock(ConfiguredAgentModelClient.class);
    private final SpringAICompatibleModel model = mock(SpringAICompatibleModel.class);
    private final SpringAIReviewClient review = new SpringAIReviewClient(properties, profiles, model);

    @Test
    void disabledOrMissingConfigurationNeverBecomesApproval() {
        assertUnavailable();
        properties.setEnabled(true);
        assertUnavailable();
        verifyNoInteractions(profiles, model);
    }

    @Test
    void identicalPrimaryAndReviewerPairIsRejected() {
        configure("qwen", "primary");

        assertThatThrownBy(() -> review.requireAvailable("qwen", "primary"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code())
                        .isEqualTo("QUALITY_REVIEW_NOT_CONFIGURED"));
        verifyNoInteractions(profiles, model);
    }

    @Test
    void sameProviderWithDifferentModelsIsAllowedButStillUsesTheAllowlist() {
        configure("qwen", "reviewer");
        when(profiles.configuredProfile("qwen", "reviewer"))
                .thenReturn(new AgentModelProperties.Profile("qwen", "reviewer", "https://api.invalid", "test"));

        review.requireAvailable("qwen", "primary");

        verify(profiles).configuredProfile("qwen", "reviewer");
        verifyNoInteractions(model);
    }

    @Test
    void missingCredentialsAreReportedAsUnavailable() {
        configure("qwen", "reviewer");
        when(profiles.configuredProfile("qwen", "reviewer"))
                .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_NOT_CONFIGURED", "private detail"));

        assertUnavailable();
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> review.requireAvailable("zhipu", "primary"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException failure = (ApiException) error;
                    assertThat(failure.status().value()).isEqualTo(503);
                    assertThat(failure.code()).isEqualTo("QUALITY_REVIEW_NOT_CONFIGURED");
                    assertThat(failure.getMessage()).doesNotContain("private detail");
                });
    }

    private void configure(String provider, String modelName) {
        properties.setEnabled(true);
        properties.setProvider(provider);
        properties.setModel(modelName);
    }
}
