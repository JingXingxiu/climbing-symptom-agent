package org.javaup.image.mapper;

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
import org.javaup.image.api.dto.ReplaceAnnotationSetRequest;
import org.javaup.image.entity.AnnotationSet;
import org.javaup.image.persistence.typehandler.UuidTypeHandler;
import org.junit.jupiter.api.Test;

class AnnotationSetMapperTest {

    private static final String INSERT_STATEMENT = AnnotationSetMapper.class.getName() + ".insertIfAbsent";

    @Test
    void annotationParsesWithoutGlobalHandlerScanningAndTargetsTheScopeUniqueIndex() {
        Configuration configuration = new Configuration();
        configuration.addMapper(AnnotationSetMapper.class);

        BoundSql boundSql = configuration.getMappedStatement(INSERT_STATEMENT).getBoundSql(new AnnotationSet());
        String sql = boundSql.getSql().replaceAll("\\s+", " ").trim();

        assertTrue(sql.startsWith("INSERT INTO annotation_set ( id, picture_id, assessment_id, owner_user_id,"));
        assertTrue(sql.contains("source_width, source_height, note"));
        assertTrue(sql.endsWith("ON CONFLICT (picture_id, owner_user_id, assessment_id) DO NOTHING"));
        assertEquals(List.of("id", "pictureId", "assessmentId", "ownerUserId", "status", "coordinateSpace",
                        "schemaVersion", "revision", "sourceWidth", "sourceHeight", "note", "createdAt", "updatedAt"),
                boundSql.getParameterMappings().stream().map(ParameterMapping::getProperty).toList());
        for (int index = 0; index < 4; index++) {
            assertInstanceOf(UuidTypeHandler.class, boundSql.getParameterMappings().get(index).getTypeHandler());
        }
    }

    @Test
    void bindsAllScopeIdsAsNativePostgresUuidsAndPersistsDimensionsAndRevision() throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(AnnotationSetMapper.class);
        MappedStatement statement = configuration.getMappedStatement(INSERT_STATEMENT);
        OffsetDateTime now = OffsetDateTime.parse("2026-10-22T08:00:00+08:00");
        AnnotationSet entity = AnnotationSet.create(UUID.randomUUID(), UUID.randomUUID(),
                new ReplaceAnnotationSetRequest(UUID.randomUUID(), 1200, 800, List.of(), "指关节酸胀", 0), now);
        PreparedStatement preparedStatement = mock(PreparedStatement.class);

        new DefaultParameterHandler(statement, entity, statement.getBoundSql(entity))
                .setParameters(preparedStatement);

        verify(preparedStatement).setObject(1, entity.getId(), Types.OTHER);
        verify(preparedStatement).setObject(2, entity.getPictureId(), Types.OTHER);
        verify(preparedStatement).setObject(3, entity.getAssessmentId(), Types.OTHER);
        verify(preparedStatement).setObject(4, entity.getOwnerUserId(), Types.OTHER);
        verify(preparedStatement).setString(5, "DRAFT");
        verify(preparedStatement).setString(6, "NORMALIZED_0_1");
        verify(preparedStatement).setInt(7, 1);
        verify(preparedStatement).setInt(8, 1);
        verify(preparedStatement).setInt(9, 1200);
        verify(preparedStatement).setInt(10, 800);
        verify(preparedStatement).setString(11, "指关节酸胀");
        verify(preparedStatement).setObject(12, now);
        verify(preparedStatement).setObject(13, now);
    }
}
