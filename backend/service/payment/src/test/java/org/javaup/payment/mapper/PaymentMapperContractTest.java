package org.javaup.payment.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.javaup.payment.entity.PaymentOrder;
import org.javaup.payment.entity.PaymentPlan;
import org.javaup.payment.entity.UserEntitlement;
import org.javaup.payment.persistence.typehandler.UuidTypeHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Validates the PostgreSQL concurrency primitives and bound parameters without executing SQL. */
class PaymentMapperContractTest {

    private Configuration configuration;

    @BeforeEach
    void setUp() {
        configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeHandlerRegistry().register(UuidTypeHandler.class);
        configuration.addMapper(PaymentOrderMapper.class);
        configuration.addMapper(UserEntitlementMapper.class);
    }

    @Test
    void orderInsertTargetsOnlyTheUserScopedIdempotencyConstraintAndBindsNativeUuids() throws Exception {
        PaymentPlan plan = new PaymentPlan();
        plan.setPlanCode("PLUS");
        plan.setName("PLUS会员");
        plan.setPriceCents(1990);
        plan.setCurrency("CNY");
        plan.setDurationDays(30);
        plan.setDailyModelCallLimit(50);
        PaymentOrder order = PaymentOrder.create(UUID.randomUUID(), UUID.randomUUID(), plan,
                OffsetDateTime.parse("2026-11-02T08:00:00Z"));
        MappedStatement statement = statement(PaymentOrderMapper.class, "insertIfAbsent");
        BoundSql sql = statement.getBoundSql(order);
        assertThat(normalize(sql)).endsWith("ON CONFLICT (user_id, idempotency_key) DO NOTHING");
        assertEquals(List.of("id", "userId", "idempotencyKey", "planCode", "planName", "priceCents", "currency",
                        "durationDays", "dailyModelCallLimit", "status", "expiresAt", "createdAt", "updatedAt"),
                sql.getParameterMappings().stream().map(ParameterMapping::getProperty).toList());
        PreparedStatement prepared = mock(PreparedStatement.class);
        new DefaultParameterHandler(statement, order, sql).setParameters(prepared);
        verify(prepared).setObject(1, order.getId(), Types.OTHER);
        verify(prepared).setObject(2, order.getUserId(), Types.OTHER);
        verify(prepared).setObject(3, order.getIdempotencyKey(), Types.OTHER);
        verify(prepared).setInt(6, 1990);
    }

    @Test
    void orderLockAlwaysScopesBothTheUserAndOrderAndUsesBoundUuidParameters() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Map<String, Object> parameters = Map.of("userId", userId, "orderId", orderId);
        MappedStatement statement = statement(PaymentOrderMapper.class, "findOwnedForUpdate");
        BoundSql sql = statement.getBoundSql(parameters);
        assertEquals("SELECT * FROM payment_order WHERE user_id = ? AND id = ? FOR UPDATE", normalize(sql));
        PreparedStatement prepared = mock(PreparedStatement.class);
        new DefaultParameterHandler(statement, parameters, sql).setParameters(prepared);
        verify(prepared).setObject(1, userId, Types.OTHER);
        verify(prepared).setObject(2, orderId, Types.OTHER);
    }

    @Test
    void differentOrderRenewalsSerializeOnASingleUserRowIncludingTheFirstPurchase() {
        BoundSql insert = statement(UserEntitlementMapper.class, "insertIfAbsent").getBoundSql(new UserEntitlement());
        assertThat(normalize(insert)).endsWith("ON CONFLICT (user_id) DO NOTHING");
        BoundSql lock = statement(UserEntitlementMapper.class, "findByUserIdForUpdate")
                .getBoundSql(Map.of("userId", UUID.randomUUID()));
        assertEquals("SELECT * FROM user_entitlement WHERE user_id = ? FOR UPDATE", normalize(lock));
        BoundSql update = statement(UserEntitlementMapper.class, "updateEntitlement")
                .getBoundSql(new UserEntitlement());
        assertThat(normalize(update)).endsWith("WHERE user_id = ?");
        assertThat(normalize(update)).contains("valid_until = ?");
        assertEquals("userId", update.getParameterMappings().getLast().getProperty());
    }

    @Test
    void recentOrdersUseAUserPredicateAndBoundLimitWithStableNewestFirstOrdering() {
        BoundSql sql = statement(PaymentOrderMapper.class, "findRecent")
                .getBoundSql(Map.of("userId", UUID.randomUUID(), "limit", 20));
        assertEquals("SELECT * FROM payment_order WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                normalize(sql));
        assertEquals(List.of("userId", "limit"),
                sql.getParameterMappings().stream().map(ParameterMapping::getProperty).toList());
    }

    @Test
    void paidUpdateIsUserScopedAndCannotOverwriteAnAlreadyPaidOrder() {
        BoundSql sql = statement(PaymentOrderMapper.class, "markPaid").getBoundSql(new PaymentOrder());
        assertThat(normalize(sql)).endsWith("WHERE user_id = ? AND id = ? AND status = 'PENDING'");
        assertEquals(List.of("paidAt", "updatedAt", "userId", "id"),
                sql.getParameterMappings().stream().map(ParameterMapping::getProperty).toList());
    }

    private MappedStatement statement(Class<?> mapper, String method) {
        return configuration.getMappedStatement(mapper.getName() + "." + method);
    }

    private static String normalize(BoundSql sql) {
        return sql.getSql().replaceAll("\\s+", " ").trim();
    }
}
