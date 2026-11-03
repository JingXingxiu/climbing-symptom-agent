package org.javaup.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("payment_plan")
public class PaymentPlan {

    @TableId(value = "plan_code", type = IdType.INPUT)
    private String planCode;

    private String name;

    private int priceCents;

    private String currency;

    private int durationDays;

    private int dailyModelCallLimit;

    private boolean active;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
