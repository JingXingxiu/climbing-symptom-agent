package org.javaup.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("payment_order")
public class PaymentOrder {

    public static final String PENDING = "PENDING";
    public static final String PAID = "PAID";
    private static final Duration ORDER_LIFETIME = Duration.ofMinutes(30);

    public static PaymentOrder create(
            UUID userId,
            UUID idempotencyKey,
            PaymentPlan plan,
            OffsetDateTime now
    ) {
        PaymentOrder order = new PaymentOrder();
        order.id = UUID.randomUUID();
        order.userId = userId;
        order.idempotencyKey = idempotencyKey;
        order.planCode = plan.getPlanCode();
        order.planName = plan.getName();
        order.priceCents = plan.getPriceCents();
        order.currency = plan.getCurrency();
        order.durationDays = plan.getDurationDays();
        order.dailyModelCallLimit = plan.getDailyModelCallLimit();
        order.status = PENDING;
        order.expiresAt = now.plus(ORDER_LIFETIME);
        order.createdAt = now;
        order.updatedAt = now;
        return order;
    }

    public void markPaid(OffsetDateTime now) {
        status = PAID;
        paidAt = now;
        updatedAt = now;
    }

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID userId;

    private UUID idempotencyKey;

    private String planCode;

    private String planName;

    private int priceCents;

    private String currency;

    private int durationDays;

    private int dailyModelCallLimit;

    private String status;

    private OffsetDateTime expiresAt;

    private OffsetDateTime paidAt;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
