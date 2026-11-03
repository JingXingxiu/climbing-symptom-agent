package org.javaup.base.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.javaup.base.api.dto.UpdateUserProfileRequest;
import org.javaup.base.api.dto.UserProfileResponse;
import org.javaup.base.api.dto.UserStatus;
import org.javaup.base.entity.UserAccount;
import org.javaup.base.entity.UserLoginIdentity;
import org.javaup.base.entity.UserProfile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class UserProfileDtoTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void omittedFieldsAreNotMarkedAsPresent() {
        var request = readRequest("""
                {"version": 1}
                """);

        assertFalse(request.hasDisplayName());
        assertFalse(request.hasAvatarPictureId());
        assertNull(request.displayName());
        assertNull(request.avatarPictureId());
        assertEquals(1, request.version());
        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void explicitNullFieldsAreMarkedAsPresent() {
        var request = readRequest("""
                {"displayName": null, "avatarPictureId": null, "version": 2}
                """);

        assertTrue(request.hasDisplayName());
        assertTrue(request.hasAvatarPictureId());
        assertNull(request.displayName());
        assertNull(request.avatarPictureId());
        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void normalValuesAreDeserializedAndMarkedAsPresent() {
        UUID avatarPictureId = UUID.randomUUID();
        var request = readRequest("""
                {"displayName": "Climber", "avatarPictureId": "%s", "version": 3}
                """.formatted(avatarPictureId));

        assertTrue(request.hasDisplayName());
        assertTrue(request.hasAvatarPictureId());
        assertEquals("Climber", request.displayName());
        assertEquals(avatarPictureId, request.avatarPictureId());
        assertEquals(3, request.version());
        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void constructorTreatsBothUpdateFieldsAsExplicitlyProvided() {
        var request = new UpdateUserProfileRequest(null, null, 1);

        assertTrue(request.hasDisplayName());
        assertTrue(request.hasAvatarPictureId());
        assertNull(request.displayName());
        assertNull(request.avatarPictureId());
    }

    @Test
    void presenceFlagsCannotBeProvidedThroughJson() {
        ObjectMapper permissiveMapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        var request = permissiveMapper.readValue("""
                {
                  "displayNamePresent": true,
                  "avatarPictureIdPresent": true,
                  "hasDisplayName": true,
                  "hasAvatarPictureId": true,
                  "version": 1
                }
                """, UpdateUserProfileRequest.class);

        assertFalse(request.hasDisplayName());
        assertFalse(request.hasAvatarPictureId());
    }

    @Test
    void serializationContainsOnlyTheExistingRequestFields() {
        var request = new UpdateUserProfileRequest("Climber", null, 1);

        assertEquals(objectMapper.readTree("""
                        {"displayName": "Climber", "avatarPictureId": null, "version": 1}
                        """),
                objectMapper.readTree(objectMapper.writeValueAsString(request)));
    }

    @Test
    void versionIsRequiredAndMustBePositiveAfterDeserialization() {
        for (String json : List.of("{}", "{\"version\":null}", "{\"version\":0}", "{\"version\":-1}")) {
            var violations = validator.validate(readRequest(json));

            assertTrue(violations.stream().anyMatch(violation ->
                    violation.getPropertyPath().toString().equals("version")), json);
        }
    }

    @Test
    void displayNameRetainsTheEightyCharacterLimitAfterDeserialization() {
        var valid = readRequest("""
                {"displayName": "%s", "version": 1}
                """.formatted("x".repeat(80)));
        var invalid = readRequest("""
                {"displayName": "%s", "version": 1}
                """.formatted("x".repeat(81)));

        assertTrue(validator.validate(valid).isEmpty());
        assertTrue(validator.validate(invalid).stream().anyMatch(violation ->
                violation.getPropertyPath().toString().equals("displayName")));
    }

    @Test
    void factoriesInitializeAccountProfileAndWechatIdentity() {
        UUID userId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-10-26T12:00:00+08:00");

        UserAccount account = UserAccount.create(userId, now);
        UserProfile profile = UserProfile.create(userId, "Climber", now);
        UserLoginIdentity identity = UserLoginIdentity.create(userId, "app-id", "open-id", now);

        assertEquals(userId, account.getId());
        assertEquals("ACTIVE", account.getAccountStatus());
        assertEquals(now, account.getCreatedAt());
        assertEquals(now, account.getUpdatedAt());
        assertNull(account.getDeletedAt());
        assertEquals(userId, profile.getUserId());
        assertEquals("Climber", profile.getDisplayName());
        assertNull(profile.getAvatarPictureId());
        assertEquals("zh-CN", profile.getLocale());
        assertEquals(1, profile.getVersion());
        assertEquals(now, profile.getCreatedAt());
        assertEquals(now, profile.getUpdatedAt());
        assertNotNull(identity.getId());
        assertNotEquals(identity.getId(), UserLoginIdentity.create(userId, "app-id", "open-id", now).getId());
        assertEquals(userId, identity.getUserId());
        assertEquals("WECHAT", identity.getIdentityType());
        assertEquals("app-id", identity.getIssuer());
        assertEquals("open-id", identity.getExternalSubject());
        assertEquals(now, identity.getCreatedAt());
        assertEquals(now, identity.getLastLoginAt());
    }

    @Test
    void applyingOmittedFieldsPreservesExistingValuesAndAdvancesVersion() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-10-26T12:00:00+08:00");
        OffsetDateTime updatedAt = createdAt.plusMinutes(1);
        UUID avatarPictureId = UUID.randomUUID();
        UserProfile profile = UserProfile.create(UUID.randomUUID(), "Climber", createdAt);
        profile.setAvatarPictureId(avatarPictureId);

        profile.apply(readRequest("{\"version\":1}"), updatedAt);

        assertEquals("Climber", profile.getDisplayName());
        assertEquals(avatarPictureId, profile.getAvatarPictureId());
        assertEquals(2, profile.getVersion());
        assertEquals(createdAt, profile.getCreatedAt());
        assertEquals(updatedAt, profile.getUpdatedAt());
    }

    @Test
    void applyingExplicitNullClearsOnlyTheProvidedField() {
        OffsetDateTime now = OffsetDateTime.parse("2026-10-26T12:00:00+08:00");
        UUID avatarPictureId = UUID.randomUUID();
        UserProfile profile = UserProfile.create(UUID.randomUUID(), "Climber", now);
        profile.setAvatarPictureId(avatarPictureId);

        profile.apply(readRequest("{\"displayName\":null,\"version\":1}"), now.plusMinutes(1));

        assertNull(profile.getDisplayName());
        assertEquals(avatarPictureId, profile.getAvatarPictureId());

        profile.apply(readRequest("{\"avatarPictureId\":null,\"version\":2}"), now.plusMinutes(2));

        assertNull(profile.getAvatarPictureId());
        assertEquals(3, profile.getVersion());
    }

    @Test
    void applyingNormalValuesUpdatesBothFields() {
        OffsetDateTime now = OffsetDateTime.parse("2026-10-26T12:00:00+08:00");
        UUID avatarPictureId = UUID.randomUUID();
        UserProfile profile = UserProfile.create(UUID.randomUUID(), "Old name", now);

        profile.apply(readRequest("""
                {"displayName": "New name", "avatarPictureId": "%s", "version": 1}
                """.formatted(avatarPictureId)), now.plusMinutes(1));

        assertEquals("New name", profile.getDisplayName());
        assertEquals(avatarPictureId, profile.getAvatarPictureId());
        assertEquals(2, profile.getVersion());
    }

    @Test
    void responseMapsAccountStatusAndProfileValues() {
        UUID userId = UUID.randomUUID();
        UUID avatarPictureId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-10-26T12:00:00+08:00");
        OffsetDateTime updatedAt = createdAt.plusMinutes(1);
        UserAccount account = UserAccount.create(userId, createdAt);
        account.setAccountStatus("DISABLED");
        UserProfile profile = UserProfile.create(userId, "Climber", createdAt);
        profile.setAvatarPictureId(avatarPictureId);
        profile.setUpdatedAt(updatedAt);

        UserProfileResponse response = UserProfileResponse.fromEntity(account, profile);

        assertEquals(userId, response.userId());
        assertEquals(UserStatus.DISABLED, response.status());
        assertEquals("Climber", response.displayName());
        assertEquals(avatarPictureId, response.avatarPictureId());
        assertEquals(1, response.version());
        assertEquals(createdAt.toInstant(), response.createdAt());
        assertEquals(updatedAt.toInstant(), response.updatedAt());
    }

    private UpdateUserProfileRequest readRequest(String json) {
        return objectMapper.readValue(json, UpdateUserProfileRequest.class);
    }
}
