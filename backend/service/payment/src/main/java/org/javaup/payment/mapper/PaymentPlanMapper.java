package org.javaup.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.javaup.payment.entity.PaymentPlan;

public interface PaymentPlanMapper extends BaseMapper<PaymentPlan> {

    @Select("SELECT * FROM payment_plan WHERE active = true ORDER BY price_cents, plan_code")
    List<PaymentPlan> findActivePlans();

    @Select("SELECT * FROM payment_plan WHERE plan_code = #{planCode} AND active = true")
    PaymentPlan findActivePlan(@Param("planCode") String planCode);
}
