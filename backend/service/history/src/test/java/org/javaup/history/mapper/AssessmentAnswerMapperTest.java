package org.javaup.history.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.javaup.history.api.dto.AppendAssessmentAnswerRequest;
import org.javaup.history.api.dto.HistoryEnums.AssessmentAnswerSource;
import org.javaup.history.entity.AssessmentAnswer;
import org.javaup.history.persistence.typehandler.PgObjectTypeHandler;
import org.javaup.history.persistence.typehandler.UuidTypeHandler;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AssessmentAnswerMapperTest {

    private static final String INSERT = AssessmentAnswerMapper.class.getName() + ".insertIfAbsent";

    @Test
    void parsesBeforePackageScanningAndUsesOnlyTheScopedClientIdConflictTarget() {
        Configuration configuration = new Configuration();
        configuration.addMapper(AssessmentAnswerMapper.class);
        BoundSql sql = configuration.getMappedStatement(INSERT).getBoundSql(new AssessmentAnswer());

        assertTrue(sql.getSql().replaceAll("\\s+", " ").trim()
                .endsWith("ON CONFLICT (assessment_id, client_answer_id) DO NOTHING"));
        assertEquals(List.of("id", "assessmentId", "clientAnswerId", "sequenceNo", "questionKey",
                        "questionText", "answer", "answerSource", "createdAt"),
                sql.getParameterMappings().stream().map(ParameterMapping::getProperty).toList());
        for (int index : List.of(0, 1, 2)) {
            assertInstanceOf(UuidTypeHandler.class, sql.getParameterMappings().get(index).getTypeHandler());
        }
        assertInstanceOf(PgObjectTypeHandler.class, sql.getParameterMappings().get(6).getTypeHandler());
    }

    @Test
    void bindsNativePostgresUuidAndJsonbRatherThanTheirStringWrappers() throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(AssessmentAnswerMapper.class);
        MappedStatement statement = configuration.getMappedStatement(INSERT);
        var mapper = JsonMapper.builder().build();
        var request = new AppendAssessmentAnswerRequest(UUID.randomUUID(), 7, "location.confirm",
                "确认位置", mapper.readTree("{\"confirmed\":true}"), AssessmentAnswerSource.USER_CORRECTION);
        AssessmentAnswer entity = AssessmentAnswer.create(UUID.randomUUID(), request,
                OffsetDateTime.parse("2026-10-25T12:00:00+08:00"), mapper);
        PreparedStatement prepared = mock(PreparedStatement.class);

        new DefaultParameterHandler(statement, entity, statement.getBoundSql(entity)).setParameters(prepared);

        verify(prepared).setObject(1, entity.getId(), Types.OTHER);
        verify(prepared).setObject(2, entity.getAssessmentId(), Types.OTHER);
        verify(prepared).setObject(3, entity.getClientAnswerId(), Types.OTHER);
        verify(prepared).setInt(4, 7);
        verify(prepared).setString(5, "location.confirm");
        verify(prepared).setString(6, "确认位置");
        verify(prepared).setObject(7, entity.getAnswer(), Types.OTHER);
        verify(prepared).setString(8, "USER_CORRECTION");
        verify(prepared).setObject(9, entity.getCreatedAt());
    }
}
