package org.javaup.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("user_entitlement")
public class UserEntitlement {

    /** Creates a lockable baseline; the first grant is applied while holding its row lock. */
    public static UserEntitlement create(
            UUID userId,
            PaymentOrder order,
            OffsetDateTime now
    ) {
        UserEntitlement entitlement = new UserEntitlement();
        entitlement.userId = userId;
        entitlement.planCode = order.getPlanCode();
        entitlement.planName = order.getPlanName();
        entitlement.dailyModelCallLimit = order.getDailyModelCallLimit();
        entitlement.validUntil = now;
        entitlement.createdAt = now;
        entitlement.updatedAt = now;
        return entitlement;
    }

    public void renew(PaymentOrder order, OffsetDateTime now) {
        OffsetDateTime start = validUntil.isAfter(now) ? validUntil : now;
        planCode = order.getPlanCode();
        planName = order.getPlanName();
        dailyModelCallLimit = order.getDailyModelCallLimit();
        validUntil = start.plusDays(order.getDurationDays());
        updatedAt = now;
    }

    @TableId(value = "user_id", type = IdType.INPUT)
    private UUID userId;

    private String planCode;

    private String planName;

    private int dailyModelCallLimit;

    private OffsetDateTime validUntil;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
