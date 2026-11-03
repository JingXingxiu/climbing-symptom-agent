package org.javaup.base.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import org.javaup.base.api.dto.UpdateUserProfileRequest;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@TableName("user_profile")
public class UserProfile {

    public static UserProfile create(UUID userId, String displayName, OffsetDateTime now) {
        UserProfile profile = new UserProfile();
        profile.userId = userId;
        profile.displayName = displayName;
        profile.locale = "zh-CN";
        profile.version = 1;
        profile.createdAt = now;
        profile.updatedAt = now;
        return profile;
    }

    public void apply(UpdateUserProfileRequest request, OffsetDateTime now) {
        if (request.hasDisplayName()) {
            displayName = request.displayName();
        }
        if (request.hasAvatarPictureId()) {
            avatarPictureId = request.avatarPictureId();
        }
        version++;
        updatedAt = now;
    }

    @TableId(value = "user_id", type = IdType.INPUT)
    private UUID userId;

    private String displayName;

    private UUID avatarPictureId;

    private String locale;

    private Integer version;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
