package org.javaup.ai.mapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface UserDailyUsageMapper {

    /** A single conditional upsert serializes reservations across sessions and instances. */
    @Insert("""
            INSERT INTO ai_user_daily_usage (user_id, usage_date, used_model_calls, created_at, updated_at)
            VALUES (#{userId}, #{usageDate}, 1, #{now}, #{now})
            ON CONFLICT (user_id, usage_date) DO UPDATE
            SET used_model_calls = ai_user_daily_usage.used_model_calls + 1, updated_at = #{now}
            WHERE ai_user_daily_usage.used_model_calls < #{dailyLimit}
            """)
    int reserve(
            @Param("userId") UUID userId,
            @Param("usageDate") LocalDate usageDate,
            @Param("dailyLimit") int dailyLimit,
            @Param("now") OffsetDateTime now
    );

    @Select("""
            SELECT used_model_calls FROM ai_user_daily_usage
            WHERE user_id = #{userId} AND usage_date = #{usageDate}
            """)
    Integer selectUsedModelCalls(
            @Param("userId") UUID userId,
            @Param("usageDate") LocalDate usageDate
    );
}
