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
@TableName("user_account")
public class UserAccount {

    public static UserAccount create(UUID userId, OffsetDateTime now) {
        UserAccount account = new UserAccount();
        account.id = userId;
        account.accountStatus = "ACTIVE";
        account.createdAt = now;
        account.updatedAt = now;
        return account;
    }

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private String accountStatus;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    private OffsetDateTime deletedAt;
}
