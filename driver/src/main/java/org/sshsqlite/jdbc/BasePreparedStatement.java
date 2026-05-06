package org.sshsqlite.jdbc;

import java.io.*;
import java.math.*;
import java.net.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class BasePreparedStatement extends BaseStatement implements java.sql.PreparedStatement {
    protected final String sql;
    public BasePreparedStatement(BaseConnection connection, String sql) { super(connection);
        this.sql = java.util.Objects.requireNonNull(sql, "sql"); }
    @Override
    public boolean execute() throws java.sql.SQLException {
        unsupportedIfOpen();
        return false;
    }

    @Override
    public void setBoolean(int arg0, boolean arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setByte(int arg0, byte arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setShort(int arg0, short arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setInt(int arg0, int arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setLong(int arg0, long arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setFloat(int arg0, float arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setDouble(int arg0, double arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setURL(int arg0, java.net.URL arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setArray(int arg0, java.sql.Array arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setTime(int arg0, java.sql.Time arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setTime(int arg0, java.sql.Time arg1, java.util.Calendar arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setDate(int arg0, java.sql.Date arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setDate(int arg0, java.sql.Date arg1, java.util.Calendar arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSetMetaData getMetaData() throws java.sql.SQLException {
        checkOpen();
        return null;
    }

    @Override
    public java.sql.ResultSet executeQuery() throws java.sql.SQLException {
        unsupportedIfOpen();
        return null;
    }

    @Override
    public void clearParameters() throws java.sql.SQLException {
        checkOpen();
    }

    @Override
    public int executeUpdate() throws java.sql.SQLException {
        unsupportedIfOpen();
        return 0;
    }

    @Override
    public long executeLargeUpdate() throws java.sql.SQLException {
        unsupportedIfOpen();
        return 0L;
    }

    @Override
    public java.sql.ParameterMetaData getParameterMetaData() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void addBatch() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setNull(int arg0, int arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setNull(int arg0, int arg1, String arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setBigDecimal(int arg0, java.math.BigDecimal arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setString(int arg0, String arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setBytes(int arg0, byte[] arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setTimestamp(int arg0, java.sql.Timestamp arg1, java.util.Calendar arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setTimestamp(int arg0, java.sql.Timestamp arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setAsciiStream(int arg0, java.io.InputStream arg1, int arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setAsciiStream(int arg0, java.io.InputStream arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setAsciiStream(int arg0, java.io.InputStream arg1, long arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setUnicodeStream(int arg0, java.io.InputStream arg1, int arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setBinaryStream(int arg0, java.io.InputStream arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setBinaryStream(int arg0, java.io.InputStream arg1, int arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setBinaryStream(int arg0, java.io.InputStream arg1, long arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setObject(int arg0, Object arg1, java.sql.SQLType arg2, int arg3) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setObject(int arg0, Object arg1, int arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setObject(int arg0, Object arg1, int arg2, int arg3) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setObject(int arg0, Object arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setObject(int arg0, Object arg1, java.sql.SQLType arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setCharacterStream(int arg0, java.io.Reader arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setCharacterStream(int arg0, java.io.Reader arg1, long arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setCharacterStream(int arg0, java.io.Reader arg1, int arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setRef(int arg0, java.sql.Ref arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setBlob(int arg0, java.io.InputStream arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setBlob(int arg0, java.sql.Blob arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setBlob(int arg0, java.io.InputStream arg1, long arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setClob(int arg0, java.io.Reader arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setClob(int arg0, java.io.Reader arg1, long arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setClob(int arg0, java.sql.Clob arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setRowId(int arg0, java.sql.RowId arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setNString(int arg0, String arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setNCharacterStream(int arg0, java.io.Reader arg1, long arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setNCharacterStream(int arg0, java.io.Reader arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setNClob(int arg0, java.sql.NClob arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setNClob(int arg0, java.io.Reader arg1, long arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setNClob(int arg0, java.io.Reader arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setSQLXML(int arg0, java.sql.SQLXML arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

}
