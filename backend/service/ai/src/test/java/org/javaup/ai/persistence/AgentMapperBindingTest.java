package org.javaup.ai.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.javaup.ai.entity.AgentCheckpoint;
import org.javaup.ai.entity.AgentSession;
import org.javaup.ai.entity.AgentStep;
import org.javaup.ai.entity.ModelCall;
import org.javaup.ai.mapper.AgentCheckpointMapper;
import org.javaup.ai.mapper.AgentSessionMapper;
import org.javaup.ai.mapper.AgentStepMapper;
import org.javaup.ai.mapper.ModelCallMapper;
import org.javaup.ai.mapper.UserDailyUsageMapper;
import org.javaup.ai.persistence.typehandler.PgObjectTypeHandler;
import org.javaup.ai.persistence.typehandler.UuidTypeHandler;
import org.javaup.ai.service.AgentJson;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 使用真正的MP SQL注入器及参数处理器，只mock PreparedStatement，不连接数据库。 */
class AgentMapperBindingTest {
    private final AgentJson json = new AgentJson(JsonMapper.builder().build());

    @Test
    void dailyReservationBindsNativeUserAndDateInOneConditionalUpsert() throws Exception {
        MybatisConfiguration configuration = configuration();
        UUID userId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 10, 28);
        Map<String, Object> parameters = Map.of(
                "userId", userId,
                "usageDate", date,
                "dailyLimit", 50,
                "now", OffsetDateTime.parse("2026-10-27T16:00:00Z"));
        MappedStatement statement = statement(configuration, UserDailyUsageMapper.class, "reserve");
        BoundSql sql = statement.getBoundSql(parameters);

        String normalized = normalized(sql);
        assertTrue(normalized.contains("ON CONFLICT (user_id, usage_date) DO UPDATE"));
        assertTrue(normalized.contains("used_model_calls = ai_user_daily_usage.used_model_calls + 1"));
        assertTrue(normalized.endsWith("WHERE ai_user_daily_usage.used_model_calls < ?"));
        PreparedStatement prepared = bind(statement, parameters, sql);
        verify(prepared).setObject(index(sql, "userId"), userId, Types.OTHER);
        verify(prepared).setObject(index(sql, "usageDate"), date);
        verify(prepared).setInt(index(sql, "dailyLimit"), 50);
    }

    @Test
    void sessionInsertConflictPathBindsNativeUuidAndJsonb() throws Exception {
        MybatisConfiguration configuration = configuration();
        AgentSession session = session();
        MappedStatement statement =
                statement(configuration, AgentSessionMapper.class, "insertIfAbsent");
        BoundSql sql = statement.getBoundSql(session);
        assertTrue(normalized(sql).endsWith("ON CONFLICT DO NOTHING"));
        PreparedStatement prepared = bind(statement, session, sql);
        assertNative(sql, prepared, "id", session.getId(), UuidTypeHandler.class);
        assertNative(sql, prepared, "userId", session.getUserId(), UuidTypeHandler.class);
        assertNative(
                sql, prepared, "assessmentId", session.getAssessmentId(), UuidTypeHandler.class);
        assertNative(
                sql,
                prepared,
                "inputSnapshot",
                session.getInputSnapshot(),
                PgObjectTypeHandler.class);
    }

    @Test
    void generatedBaseMapperInsertsKeepJsonbAndUuidHandlersForAllFourEntities() throws Exception {
        MybatisConfiguration configuration = configuration();
        UUID sessionId = UUID.randomUUID();
        AgentStep step = new AgentStep();
        step.setId(UUID.randomUUID());
        step.setSessionId(sessionId);
        step.setStepNo(1);
        step.setStepType("USER_INPUT");
        step.setStatus("COMPLETED");
        step.setAnswer(json.pg(json.node(List.of("酸", "麻"))));
        step.setDecision(json.pg(json.object()));
        AgentCheckpoint point = new AgentCheckpoint();
        point.setId(UUID.randomUUID());
        point.setSessionId(sessionId);
        point.setState(json.pg(json.object()));
        point.setCheckpointNo(1);
        point.setStateSchemaVersion(1);
        ModelCall call = new ModelCall();
        call.setId(UUID.randomUUID());
        call.setSessionId(sessionId);
        call.setResponseMetadata(json.pg(json.object()));
        call.setStatus("STARTED");
        for (InsertCase item :
                List.of(
                        new InsertCase(AgentSessionMapper.class, session(), "inputSnapshot"),
                        new InsertCase(AgentStepMapper.class, step, "answer"),
                        new InsertCase(AgentCheckpointMapper.class, point, "state"),
                        new InsertCase(ModelCallMapper.class, call, "responseMetadata"))) {
            MappedStatement statement = statement(configuration, item.mapper(), "insert");
            BoundSql sql = statement.getBoundSql(item.entity());
            PreparedStatement prepared = bind(statement, item.entity(), sql);
            Object id = configuration.newMetaObject(item.entity()).getValue("id");
            Object payload =
                    configuration.newMetaObject(item.entity()).getValue(item.jsonProperty());
            assertNative(sql, prepared, "id", id, UuidTypeHandler.class);
            assertNative(sql, prepared, item.jsonProperty(), payload, PgObjectTypeHandler.class);
        }
    }

    @Test
    void customQueriesOnAllFourMappersBindActualUuidValuesRatherThanStrings() throws Exception {
        MybatisConfiguration configuration = configuration();
        UUID userId = UUID.randomUUID(),
                sessionId = UUID.randomUUID(),
                operationId = UUID.randomUUID(),
                stepId = UUID.randomUUID();
        for (QueryCase item :
                List.of(
                        new QueryCase(
                                AgentSessionMapper.class,
                                "selectOwnedForUpdate",
                                Map.of("userId", userId, "sessionId", sessionId),
                                "sessionId",
                                sessionId),
                        new QueryCase(
                                AgentStepMapper.class,
                                "selectByOperation",
                                Map.of("sessionId", sessionId, "operationId", operationId),
                                "operationId",
                                operationId),
                        new QueryCase(
                                AgentCheckpointMapper.class,
                                "selectByStep",
                                Map.of("sessionId", sessionId, "stepId", stepId),
                                "stepId",
                                stepId),
                        new QueryCase(
                                ModelCallMapper.class,
                                "discardInflight",
                                Map.of(
                                        "sessionId",
                                        sessionId,
                                        "generation",
                                        3L,
                                        "now",
                                        OffsetDateTime.parse("2026-10-27T02:00:00Z")),
                                "sessionId",
                                sessionId))) {
            MappedStatement statement = statement(configuration, item.mapper(), item.method());
            BoundSql sql = statement.getBoundSql(item.parameters());
            PreparedStatement prepared = bind(statement, item.parameters(), sql);
            // @Param Map使用UnknownTypeHandler的动态分派，最终也必须落到本服务的UUID处理器。
            verify(prepared).setObject(index(sql, item.uuidProperty()), item.uuid(), Types.OTHER);
        }
    }

    @Test
    void explicitSessionUpdateBindsNullsInsteadOfLeavingOldDeadlineAndTermination()
            throws Exception {
        MybatisConfiguration configuration = configuration();
        AgentSession resumed = session();
        resumed.setStatus("CREATED");
        resumed.setExecutionDeadlineAt(null);
        resumed.setTerminationReasonCode(null);
        resumed.setTerminationDetail(null);
        resumed.setCompletedAt(null);
        MappedStatement statement =
                statement(configuration, AgentSessionMapper.class, "updateState");
        BoundSql sql = statement.getBoundSql(resumed);
        PreparedStatement prepared = bind(statement, resumed, sql);
        String normalized = normalized(sql);
        assertTrue(normalized.matches(".*execution_deadline_at\\s*=\\s*\\?.*"));
        assertTrue(normalized.matches(".*termination_reason_code\\s*=\\s*\\?.*"));
        assertTrue(normalized.matches(".*termination_detail\\s*=\\s*\\?.*"));
        for (String property :
                List.of(
                        "executionDeadlineAt",
                        "terminationReasonCode",
                        "terminationDetail",
                        "completedAt")) {
            verify(prepared).setNull(index(sql, property), Types.OTHER);
        }
        verify(prepared).setObject(index(sql, "id"), resumed.getId(), Types.OTHER);
        verify(prepared).setObject(index(sql, "userId"), resumed.getUserId(), Types.OTHER);
    }

    private MybatisConfiguration configuration() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeHandlerRegistry().register("org.javaup.ai.persistence.typehandler");
        configuration.addMapper(AgentSessionMapper.class);
        configuration.addMapper(AgentStepMapper.class);
        configuration.addMapper(AgentCheckpointMapper.class);
        configuration.addMapper(ModelCallMapper.class);
        configuration.addMapper(UserDailyUsageMapper.class);
        return configuration;
    }

    private AgentSession session() {
        AgentSession row = new AgentSession();
        row.setId(UUID.randomUUID());
        row.setUserId(UUID.randomUUID());
        row.setAssessmentId(UUID.randomUUID());
        row.setClientRequestId(UUID.randomUUID());
        row.setStatus("CREATED");
        row.setInputSnapshot(json.pg(json.object()));
        row.setVersion(1L);
        return row;
    }

    private MappedStatement statement(
            MybatisConfiguration configuration, Class<?> mapper, String method) {
        return configuration.getMappedStatement(mapper.getName() + "." + method);
    }

    private PreparedStatement bind(MappedStatement statement, Object value, BoundSql sql)
            throws Exception {
        PreparedStatement prepared = mock(PreparedStatement.class);
        new DefaultParameterHandler(statement, value, sql).setParameters(prepared);
        return prepared;
    }

    private void assertNative(
            BoundSql sql,
            PreparedStatement prepared,
            String property,
            Object value,
            Class<?> handler)
            throws Exception {
        int index = index(sql, property);
        assertInstanceOf(handler, sql.getParameterMappings().get(index - 1).getTypeHandler());
        verify(prepared).setObject(index, value, Types.OTHER);
    }

    private int index(BoundSql sql, String property) {
        for (int i = 0; i < sql.getParameterMappings().size(); i++) {
            if (sql.getParameterMappings().get(i).getProperty().equals(property)) return i + 1;
        }
        throw new AssertionError("No bound parameter for " + property + ": " + sql.getSql());
    }

    private String normalized(BoundSql sql) {
        return sql.getSql().replaceAll("\\s+", " ").trim();
    }

    private record InsertCase(Class<?> mapper, Object entity, String jsonProperty) {}

    private record QueryCase(
            Class<?> mapper,
            String method,
            Map<String, Object> parameters,
            String uuidProperty,
            UUID uuid) {}
}
