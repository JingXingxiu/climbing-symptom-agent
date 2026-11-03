package org.javaup.ai.persistence.typehandler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/** Binds PostgreSQL JSONB directly, without serializing the PGobject wrapper. */
@MappedTypes(PGobject.class)
@MappedJdbcTypes(value = JdbcType.OTHER, includeNullJdbcType = true)
public final class PgObjectTypeHandler extends BaseTypeHandler<PGobject> {

    @Override
    public void setNonNullParameter(
            PreparedStatement statement, int index, PGobject parameter, JdbcType jdbcType)
            throws SQLException {
        statement.setObject(index, parameter, Types.OTHER);
    }

    @Override
    public PGobject getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return (PGobject) resultSet.getObject(columnName);
    }

    @Override
    public PGobject getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return (PGobject) resultSet.getObject(columnIndex);
    }

    @Override
    public PGobject getNullableResult(CallableStatement statement, int columnIndex)
            throws SQLException {
        return (PGobject) statement.getObject(columnIndex);
    }
}
