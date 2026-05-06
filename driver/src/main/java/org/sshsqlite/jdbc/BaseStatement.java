package org.sshsqlite.jdbc;

import java.io.*;
import java.math.*;
import java.net.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class BaseStatement implements java.sql.Statement {
    protected final BaseConnection connection;
    private boolean closed;
    private int maxFieldSize;
    private int maxRows;
    private long largeMaxRows;
    private int queryTimeout;
    private int fetchDirection = ResultSet.FETCH_FORWARD;
    private int fetchSize;
    private boolean escapeProcessing = true;
    private boolean poolable;
    private boolean closeOnCompletion;
    private ResultSet currentResultSet;
    protected void checkOpen() throws SQLException { if (closed) throw JdbcSupport.closedObject(); connection.checkOpen(); }
    protected void unsupportedIfOpen() throws SQLException { checkOpen(); throw JdbcSupport.unsupported(); }
    public BaseStatement(BaseConnection connection) { this.connection = java.util.Objects.requireNonNull(connection, "connection"); }
    @Override
    public boolean execute(String arg0, int[] arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean execute(String arg0, String[] arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean execute(String arg0, int arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean execute(String arg0) throws java.sql.SQLException {
        unsupportedIfOpen();
        return false;
    }

    @Override
    public void close() throws java.sql.SQLException {
        if (!closed) { closed = true; if (currentResultSet != null) currentResultSet.close(); connection.unregister(this); }
    }

    @Override
    public void cancel() throws java.sql.SQLException {
        checkOpen();
        if (connection instanceof SshSqliteConnection) {
            ((SshSqliteConnection) connection).cancelActiveStatement();
            return;
        }
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean isClosed() throws java.sql.SQLException {
        return closed;
    }

    @Override
    public java.sql.SQLWarning getWarnings() throws java.sql.SQLException {
        checkOpen();
        return null;
    }

    @Override
    public void clearWarnings() throws java.sql.SQLException {
        checkOpen();
    }

    @Override
    public java.sql.Connection getConnection() throws java.sql.SQLException {
        checkOpen();
        return connection;
    }

    @Override
    public java.sql.ResultSet executeQuery(String arg0) throws java.sql.SQLException {
        unsupportedIfOpen();
        return null;
    }

    @Override
    public java.sql.ResultSet getResultSet() throws java.sql.SQLException {
        checkOpen();
        return currentResultSet;
    }

    @Override
    public int getUpdateCount() throws java.sql.SQLException {
        checkOpen();
        return -1;
    }

    @Override
    public long getLargeUpdateCount() throws java.sql.SQLException {
        checkOpen();
        return -1L;
    }

    @Override
    public boolean getMoreResults() throws java.sql.SQLException {
        checkOpen();
        currentResultSet = null;
        return false;
    }

    @Override
    public boolean getMoreResults(int arg0) throws java.sql.SQLException {
        checkOpen();
        currentResultSet = null;
        return false;
    }

    @Override
    public void setMaxFieldSize(int arg0) throws java.sql.SQLException {
        checkOpen();
        if (arg0 < 0) throw JdbcSupport.invalidArgument("max field size must be >= 0");
        maxFieldSize = arg0;
    }

    @Override
    public int getMaxFieldSize() throws java.sql.SQLException {
        checkOpen();
        return maxFieldSize;
    }

    @Override
    public void setMaxRows(int arg0) throws java.sql.SQLException {
        checkOpen();
        if (arg0 < 0) throw JdbcSupport.invalidArgument("max rows must be >= 0");
        maxRows = arg0;
    }

    @Override
    public int getMaxRows() throws java.sql.SQLException {
        checkOpen();
        return maxRows;
    }

    @Override
    public void setLargeMaxRows(long arg0) throws java.sql.SQLException {
        checkOpen();
        if (arg0 < 0) throw JdbcSupport.invalidArgument("max rows must be >= 0");
        largeMaxRows = arg0;
    }

    @Override
    public long getLargeMaxRows() throws java.sql.SQLException {
        checkOpen();
        return largeMaxRows;
    }

    @Override
    public void setQueryTimeout(int arg0) throws java.sql.SQLException {
        checkOpen();
        if (arg0 < 0) throw JdbcSupport.invalidArgument("query timeout must be >= 0");
        queryTimeout = arg0;
    }

    @Override
    public int getQueryTimeout() throws java.sql.SQLException {
        checkOpen();
        return queryTimeout;
    }

    @Override
    public void setFetchDirection(int arg0) throws java.sql.SQLException {
        checkOpen();
        if (arg0 != ResultSet.FETCH_FORWARD) throw JdbcSupport.unsupported();
        fetchDirection = arg0;
    }

    @Override
    public int getFetchDirection() throws java.sql.SQLException {
        checkOpen();
        return fetchDirection;
    }

    @Override
    public void setFetchSize(int arg0) throws java.sql.SQLException {
        checkOpen();
        if (arg0 < 0) throw JdbcSupport.invalidArgument("fetch size must be >= 0");
        fetchSize = arg0;
    }

    @Override
    public int getFetchSize() throws java.sql.SQLException {
        checkOpen();
        return fetchSize;
    }

    @Override
    public void setEscapeProcessing(boolean arg0) throws java.sql.SQLException {
        checkOpen();
        escapeProcessing = arg0;
    }

    @Override
    public void setPoolable(boolean arg0) throws java.sql.SQLException {
        checkOpen();
        poolable = arg0;
    }

    @Override
    public boolean isPoolable() throws java.sql.SQLException {
        checkOpen();
        return poolable;
    }

    @Override
    public void closeOnCompletion() throws java.sql.SQLException {
        checkOpen();
        closeOnCompletion = true;
    }

    @Override
    public boolean isCloseOnCompletion() throws java.sql.SQLException {
        checkOpen();
        return closeOnCompletion;
    }

    @Override
    public int executeUpdate(String arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int executeUpdate(String arg0, int arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int executeUpdate(String arg0, int[] arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int executeUpdate(String arg0, String[] arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public long executeLargeUpdate(String arg0, int arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public long executeLargeUpdate(String arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public long executeLargeUpdate(String arg0, int[] arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public long executeLargeUpdate(String arg0, String[] arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setCursorName(String arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getResultSetConcurrency() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getResultSetType() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void addBatch(String arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void clearBatch() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int[] executeBatch() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.ResultSet getGeneratedKeys() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public int getResultSetHoldability() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public long[] executeLargeBatch() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public String enquoteLiteral(String arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public String enquoteIdentifier(String arg0, boolean arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean isSimpleIdentifier(String arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public String enquoteNCharLiteral(String arg0) throws java.sql.SQLException {
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
