package org.javaup.base.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Successful login with a short-lived base-service-issued access token.
 */
public record AuthSessionResponse(
        @NotBlank String accessToken,
        @Positive long expiresInSeconds,
        @Valid UserProfileResponse user
) {
}
