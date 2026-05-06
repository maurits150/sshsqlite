package org.sshsqlite.jdbc;

import java.io.*;
import java.math.*;
import java.net.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class BaseResultSetMetaData implements java.sql.ResultSetMetaData {
    public static final class Column {
        private final String label;
        private final String name;
        private final int type;
        private final String typeName;
        private final String className;
        private final boolean nullable;

        public Column(String label, String name, int type, String typeName, String className, boolean nullable) {
            this.label = label;
            this.name = name;
            this.type = type;
            this.typeName = typeName;
            this.className = className;
            this.nullable = nullable;
        }

        public String label() { return label; }
        public String name() { return name; }
        public int type() { return type; }
        public String typeName() { return typeName; }
        public String className() { return className; }
        public boolean nullable() { return nullable; }
    }
    private final java.util.List<Column> columns;
    protected void checkOpen() throws SQLException { }
    private Column column(int index) throws SQLException { if (index < 1 || index > columns.size()) throw JdbcSupport.invalidArgument("Column index out of range: " + index); return columns.get(index - 1); }
    public BaseResultSetMetaData() { this(java.util.List.of()); }
    public BaseResultSetMetaData(java.util.List<Column> columns) { this.columns = java.util.List.copyOf(columns); }
    @Override
    public boolean isReadOnly(int arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean isSigned(int arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getPrecision(int arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean isWritable(int arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean isCaseSensitive(int arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getColumnCount() throws java.sql.SQLException {
        return columns.size();
    }

    @Override
    public String getColumnLabel(int arg0) throws java.sql.SQLException {
        return column(arg0).label();
    }

    @Override
    public String getColumnName(int arg0) throws java.sql.SQLException {
        return column(arg0).name();
    }

    @Override
    public int getColumnType(int arg0) throws java.sql.SQLException {
        return column(arg0).type();
    }

    @Override
    public String getColumnTypeName(int arg0) throws java.sql.SQLException {
        return column(arg0).typeName();
    }

    @Override
    public String getColumnClassName(int arg0) throws java.sql.SQLException {
        return column(arg0).className();
    }

    @Override
    public int isNullable(int arg0) throws java.sql.SQLException {
        return column(arg0).nullable() ? ResultSetMetaData.columnNullable : ResultSetMetaData.columnNoNulls;
    }

    @Override
    public String getCatalogName(int arg0) throws java.sql.SQLException {
        column(arg0); return "";
    }

    @Override
    public String getSchemaName(int arg0) throws java.sql.SQLException {
        column(arg0); return "";
    }

    @Override
    public String getTableName(int arg0) throws java.sql.SQLException {
        column(arg0); return "";
    }

    @Override
    public boolean isAutoIncrement(int arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean isSearchable(int arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean isCurrency(int arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getColumnDisplaySize(int arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getScale(int arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean isDefinitelyWritable(int arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public <T> T unwrap(Class<T> arg0) throws java.sql.SQLException {
        return JdbcSupport.unwrap(this, arg0);
    }

    @Override
    public boolean isWrapperFor(Class<?> arg0) throws java.sql.SQLException {
        return JdbcSupport.isWrapperFor(this, arg0);
    }

}
