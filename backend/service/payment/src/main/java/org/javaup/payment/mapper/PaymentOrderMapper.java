package org.javaup.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.javaup.payment.entity.PaymentOrder;

public interface PaymentOrderMapper extends BaseMapper<PaymentOrder> {

    @Insert("""
            INSERT INTO payment_order (
                id, user_id, idempotency_key, plan_code, plan_name, price_cents, currency,
                duration_days, daily_model_call_limit, status, expires_at, created_at, updated_at
            ) VALUES (
                #{id}, #{userId}, #{idempotencyKey}, #{planCode}, #{planName}, #{priceCents}, #{currency},
                #{durationDays}, #{dailyModelCallLimit}, #{status}, #{expiresAt}, #{createdAt}, #{updatedAt}
            )
            ON CONFLICT (user_id, idempotency_key) DO NOTHING
            """)
    int insertIfAbsent(PaymentOrder order);

    @Select("""
            SELECT * FROM payment_order
            WHERE user_id = #{userId} AND idempotency_key = #{idempotencyKey}
            """)
    PaymentOrder findByIdempotencyKey(
            @Param("userId") UUID userId,
            @Param("idempotencyKey") UUID idempotencyKey
    );

    @Select("SELECT * FROM payment_order WHERE user_id = #{userId} AND id = #{orderId}")
    PaymentOrder findOwned(@Param("userId") UUID userId, @Param("orderId") UUID orderId);

    @Select("SELECT * FROM payment_order WHERE user_id = #{userId} AND id = #{orderId} FOR UPDATE")
    PaymentOrder findOwnedForUpdate(@Param("userId") UUID userId, @Param("orderId") UUID orderId);

    @Select("""
            SELECT * FROM payment_order WHERE user_id = #{userId}
            ORDER BY created_at DESC, id DESC LIMIT #{limit}
            """)
    List<PaymentOrder> findRecent(@Param("userId") UUID userId, @Param("limit") int limit);

    @Update("""
            UPDATE payment_order SET status = 'PAID', paid_at = #{paidAt}, updated_at = #{updatedAt}
            WHERE user_id = #{userId} AND id = #{id} AND status = 'PENDING'
            """)
    int markPaid(PaymentOrder order);
}
