package org.javaup.history.persistence.typehandler;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

class PgObjectTypeHandlerTest {

    @Test
    void registersNativeJsonbForGeneratedParametersAndResultMappings() {
        TypeHandlerRegistry registry = new TypeHandlerRegistry();
        registry.register("org.javaup.history.persistence.typehandler");

        assertInstanceOf(PgObjectTypeHandler.class, registry.getTypeHandler(PGobject.class));
        assertInstanceOf(PgObjectTypeHandler.class,
                registry.getTypeHandler(PGobject.class, JdbcType.OTHER));
    }

    @Test
    void preservesJsonbObjectAndSqlNullOnNativeJdbcPaths() throws Exception {
        PgObjectTypeHandler handler = new PgObjectTypeHandler();
        PreparedStatement prepared = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        CallableStatement callable = mock(CallableStatement.class);
        PGobject json = new PGobject();
        json.setType("jsonb");
        json.setValue("{\"症状\":\"酸\"}");

        handler.setParameter(prepared, 1, json, JdbcType.OTHER);
        handler.setParameter(prepared, 2, null, JdbcType.OTHER);
        verify(prepared).setObject(1, json, Types.OTHER);
        verify(prepared).setNull(2, Types.OTHER);
        when(rows.getObject("answer")).thenReturn(json);
        when(rows.getObject(1)).thenReturn(json);
        when(callable.getObject(1)).thenReturn(json);
        assertSame(json, handler.getResult(rows, "answer"));
        assertSame(json, handler.getResult(rows, 1));
        assertSame(json, handler.getResult(callable, 1));
        assertNull(handler.getResult(rows, "missing"));
        assertNull(handler.getResult(rows, 2));
        assertNull(handler.getResult(callable, 2));
    }
}
