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
import java.util.UUID;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.junit.jupiter.api.Test;

class UuidTypeHandlerTest {

    @Test
    void bindsAndReadsNativeUuidAndPreservesSqlNull() throws Exception {
        UuidTypeHandler handler = new UuidTypeHandler();
        PreparedStatement prepared = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        CallableStatement callable = mock(CallableStatement.class);
        UUID id = UUID.randomUUID();

        handler.setParameter(prepared, 1, id, JdbcType.OTHER);
        handler.setParameter(prepared, 2, null, JdbcType.OTHER);

        verify(prepared).setObject(1, id, Types.OTHER);
        verify(prepared).setNull(2, Types.OTHER);
        when(resultSet.getObject("id", UUID.class)).thenReturn(id);
        when(resultSet.getObject(1, UUID.class)).thenReturn(id);
        when(callable.getObject(1, UUID.class)).thenReturn(id);
        assertSame(id, handler.getResult(resultSet, "id"));
        assertSame(id, handler.getResult(resultSet, 1));
        assertSame(id, handler.getResult(callable, 1));
        assertNull(handler.getResult(resultSet, "nullable_id"));
        assertNull(handler.getResult(resultSet, 2));
        assertNull(handler.getResult(callable, 2));
    }

    @Test
    void packageScanRegistersHandlerForUuidWithAndWithoutExplicitJdbcType() {
        TypeHandlerRegistry registry = new TypeHandlerRegistry();

        registry.register("org.javaup.history.persistence.typehandler");

        assertInstanceOf(UuidTypeHandler.class, registry.getTypeHandler(UUID.class, JdbcType.OTHER));
        assertInstanceOf(UuidTypeHandler.class, registry.getTypeHandler(UUID.class));
    }
}
