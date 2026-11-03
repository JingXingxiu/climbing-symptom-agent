package org.javaup.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.javaup.payment.entity.UserEntitlement;

public interface UserEntitlementMapper extends BaseMapper<UserEntitlement> {

    @Insert("""
            INSERT INTO user_entitlement (
                user_id, plan_code, plan_name, daily_model_call_limit, valid_until, created_at, updated_at
            ) VALUES (
                #{userId}, #{planCode}, #{planName}, #{dailyModelCallLimit}, #{validUntil}, #{createdAt}, #{updatedAt}
            )
            ON CONFLICT (user_id) DO NOTHING
            """)
    int insertIfAbsent(UserEntitlement entitlement);

    @Select("SELECT * FROM user_entitlement WHERE user_id = #{userId}")
    UserEntitlement findByUserId(@Param("userId") UUID userId);

    @Select("SELECT * FROM user_entitlement WHERE user_id = #{userId} FOR UPDATE")
    UserEntitlement findByUserIdForUpdate(@Param("userId") UUID userId);

    @Update("""
            UPDATE user_entitlement
            SET plan_code = #{planCode}, plan_name = #{planName}, daily_model_call_limit = #{dailyModelCallLimit},
                valid_until = #{validUntil}, updated_at = #{updatedAt}
            WHERE user_id = #{userId}
            """)
    int updateEntitlement(UserEntitlement entitlement);
}
