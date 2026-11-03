package org.javaup.image.persistence.typehandler;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.UUID;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

class PostgresTypeHandlersTest {

    @Test
    void uuidBindsNativeValueAndSqlNull() throws Exception {
        UuidTypeHandler handler = new UuidTypeHandler();
        PreparedStatement statement = mock(PreparedStatement.class);
        UUID id = UUID.randomUUID();

        handler.setParameter(statement, 1, id, JdbcType.OTHER);
        handler.setParameter(statement, 2, null, JdbcType.OTHER);

        verify(statement).setObject(1, id, Types.OTHER);
        verify(statement).setNull(2, Types.OTHER);
    }

    @Test
    void uuidReadsNativeValueThroughEachJdbcAccessPath() throws Exception {
        UuidTypeHandler handler = new UuidTypeHandler();
        ResultSet resultSet = mock(ResultSet.class);
        CallableStatement statement = mock(CallableStatement.class);
        UUID id = UUID.randomUUID();
        when(resultSet.getObject("id", UUID.class)).thenReturn(id);
        when(resultSet.getObject(1, UUID.class)).thenReturn(id);
        when(statement.getObject(1, UUID.class)).thenReturn(id);

        assertSame(id, handler.getResult(resultSet, "id"));
        assertSame(id, handler.getResult(resultSet, 1));
        assertSame(id, handler.getResult(statement, 1));
    }

    @Test
    void uuidPreservesSqlNullThroughEachJdbcAccessPath() throws Exception {
        UuidTypeHandler handler = new UuidTypeHandler();
        ResultSet resultSet = mock(ResultSet.class);
        CallableStatement statement = mock(CallableStatement.class);

        assertNull(handler.getResult(resultSet, "id"));
        assertNull(handler.getResult(resultSet, 1));
        assertNull(handler.getResult(statement, 1));
    }

    @Test
    void jsonbBindsPgObjectAndSqlNullWithoutSerializingTheWrapper() throws Exception {
        PgObjectTypeHandler handler = new PgObjectTypeHandler();
        PreparedStatement statement = mock(PreparedStatement.class);
        PGobject metadata = jsonb();

        handler.setParameter(statement, 1, metadata, JdbcType.OTHER);
        handler.setParameter(statement, 2, null, JdbcType.OTHER);

        verify(statement).setObject(1, metadata, Types.OTHER);
        verify(statement).setNull(2, Types.OTHER);
    }

    @Test
    void jsonbReadsNativeValueThroughEachJdbcAccessPath() throws Exception {
        PgObjectTypeHandler handler = new PgObjectTypeHandler();
        ResultSet resultSet = mock(ResultSet.class);
        CallableStatement statement = mock(CallableStatement.class);
        PGobject metadata = jsonb();
        when(resultSet.getObject("safe_metadata")).thenReturn(metadata);
        when(resultSet.getObject(1)).thenReturn(metadata);
        when(statement.getObject(1)).thenReturn(metadata);

        assertSame(metadata, handler.getResult(resultSet, "safe_metadata"));
        assertSame(metadata, handler.getResult(resultSet, 1));
        assertSame(metadata, handler.getResult(statement, 1));
    }

    @Test
    void jsonbPreservesSqlNullThroughEachJdbcAccessPath() throws Exception {
        PgObjectTypeHandler handler = new PgObjectTypeHandler();
        ResultSet resultSet = mock(ResultSet.class);
        CallableStatement statement = mock(CallableStatement.class);

        assertNull(handler.getResult(resultSet, "view_state"));
        assertNull(handler.getResult(resultSet, 1));
        assertNull(handler.getResult(statement, 1));
    }

    private PGobject jsonb() throws Exception {
        PGobject value = new PGobject();
        value.setType("jsonb");
        value.setValue("{\"width\":120}");
        return value;
    }
}
