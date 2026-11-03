package org.javaup.base.api.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Transport DTO for optimistic profile updates.
 */
public class UpdateUserProfileRequest {

    @Size(max = 80)
    private String displayName;

    private UUID avatarPictureId;

    @NotNull
    @Positive
    private Integer version;

    @JsonIgnore
    private boolean displayNamePresent;

    @JsonIgnore
    private boolean avatarPictureIdPresent;

    public UpdateUserProfileRequest() {
    }

    @JsonCreator(mode = JsonCreator.Mode.DISABLED)
    public UpdateUserProfileRequest(String displayName, UUID avatarPictureId, Integer version) {
        setDisplayName(displayName);
        setAvatarPictureId(avatarPictureId);
        this.version = version;
    }

    @JsonProperty("displayName")
    public String displayName() {
        return displayName;
    }

    @JsonSetter("displayName")
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
        this.displayNamePresent = true;
    }

    @JsonProperty("avatarPictureId")
    public UUID avatarPictureId() {
        return avatarPictureId;
    }

    @JsonSetter("avatarPictureId")
    public void setAvatarPictureId(UUID avatarPictureId) {
        this.avatarPictureId = avatarPictureId;
        this.avatarPictureIdPresent = true;
    }

    @JsonProperty("version")
    public Integer version() {
        return version;
    }

    @JsonSetter("version")
    public void setVersion(Integer version) {
        this.version = version;
    }

    @JsonIgnore
    public boolean hasDisplayName() {
        return displayNamePresent;
    }

    @JsonIgnore
    public boolean hasAvatarPictureId() {
        return avatarPictureIdPresent;
    }
}
