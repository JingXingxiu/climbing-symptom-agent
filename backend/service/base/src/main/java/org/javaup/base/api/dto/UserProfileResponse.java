package org.javaup.base.api.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.javaup.base.entity.UserAccount;
import org.javaup.base.entity.UserProfile;

/**
 * base-service-owned profile view. {@code avatarPictureId} is only an external
 * business reference and is not a cross-service database relationship.
 */
public record UserProfileResponse(
        @NotNull UUID userId,
        @NotNull UserStatus status,
        @Size(max = 80) String displayName,
        UUID avatarPictureId,
        @Positive int version,
        @NotNull Instant createdAt,
        @NotNull Instant updatedAt
) {
    public static UserProfileResponse fromEntity(UserAccount account, UserProfile profile) {
        return new UserProfileResponse(
                account.getId(),
                UserStatus.valueOf(account.getAccountStatus()),
                profile.getDisplayName(),
                profile.getAvatarPictureId(),
                profile.getVersion(),
                profile.getCreatedAt().toInstant(),
                profile.getUpdatedAt().toInstant());
    }
}
