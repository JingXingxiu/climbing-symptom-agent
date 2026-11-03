package org.javaup.base.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@TableName("user_login_identity")
public class UserLoginIdentity {

    public static UserLoginIdentity create(UUID userId, String appId, String openId, OffsetDateTime now) {
        UserLoginIdentity identity = new UserLoginIdentity();
        identity.id = UUID.randomUUID();
        identity.userId = userId;
        identity.identityType = "WECHAT";
        identity.issuer = appId;
        identity.externalSubject = openId;
        identity.createdAt = now;
        identity.lastLoginAt = now;
        return identity;
    }

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID userId;

    private String identityType;

    private String issuer;

    private String externalSubject;

    private OffsetDateTime createdAt;

    private OffsetDateTime lastLoginAt;
}
