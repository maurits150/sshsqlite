package org.sshsqlite.jdbc;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

public final class SshSqliteResultSetMetaData extends BaseResultSetMetaData {
    public SshSqliteResultSetMetaData() { super(); }
    public SshSqliteResultSetMetaData(List<Column> columns) { super(columns); }

    @Override public boolean isReadOnly(int column) throws SQLException { checkOpen(); getColumnName(column); return true; }
    @Override public boolean isWritable(int column) throws SQLException { checkOpen(); getColumnName(column); return false; }
    @Override public boolean isDefinitelyWritable(int column) throws SQLException { checkOpen(); getColumnName(column); return false; }
    @Override public boolean isCaseSensitive(int column) throws SQLException { checkOpen(); getColumnName(column); return true; }
    @Override public boolean isSearchable(int column) throws SQLException { checkOpen(); getColumnName(column); return true; }
    @Override public boolean isCurrency(int column) throws SQLException { checkOpen(); getColumnName(column); return false; }
    @Override public boolean isAutoIncrement(int column) throws SQLException { checkOpen(); getColumnName(column); return false; }
    @Override public int getPrecision(int column) throws SQLException { checkOpen(); getColumnName(column); return 0; }
    @Override public int getScale(int column) throws SQLException { checkOpen(); getColumnName(column); return 0; }
    @Override public int getColumnDisplaySize(int column) throws SQLException { checkOpen(); getColumnName(column); return 0; }
    @Override public boolean isSigned(int column) throws SQLException {
        int type = getColumnType(column);
        return type == Types.INTEGER || type == Types.BIGINT || type == Types.REAL || type == Types.FLOAT || type == Types.DOUBLE || type == Types.NUMERIC || type == Types.DECIMAL;
    }
}
