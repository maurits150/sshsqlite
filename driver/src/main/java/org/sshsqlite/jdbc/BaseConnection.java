package org.sshsqlite.jdbc;

import java.io.*;
import java.math.*;
import java.net.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class BaseConnection implements java.sql.Connection {
    private boolean closed;
    private boolean autoCommit = true;
    private boolean readOnly = false;
    private String catalog;
    private String schema = "main";
    private int transactionIsolation = Connection.TRANSACTION_SERIALIZABLE;
    private int holdability = ResultSet.CLOSE_CURSORS_AT_COMMIT;
    private int networkTimeout;
    private final Properties clientInfo = new Properties();
    private final java.util.Set<BaseStatement> statements = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    protected void checkOpen() throws SQLException { if (closed) throw JdbcSupport.closedConnection(); }
    void register(BaseStatement statement) { statements.add(statement); }
    void unregister(BaseStatement statement) { statements.remove(statement); }
    public BaseConnection() { }
    @Override
    public void setReadOnly(boolean arg0) throws java.sql.SQLException {
        checkOpen();
        readOnly = arg0;
    }

    @Override
    public void close() throws java.sql.SQLException {
        if (!closed) { closeRegisteredStatements(); closed = true; }
    }

    protected final void closeRegisteredStatements() throws SQLException {
        SQLException failure = null;
        for (BaseStatement statement : java.util.List.copyOf(statements)) {
            try {
                statement.close();
            } catch (SQLException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
        }
        statements.clear();
        if (failure != null) throw failure;
    }

    protected final void resetBaseMutableState() {
        autoCommit = true;
        readOnly = false;
        catalog = null;
        schema = "main";
        transactionIsolation = Connection.TRANSACTION_SERIALIZABLE;
        holdability = ResultSet.CLOSE_CURSORS_AT_COMMIT;
        networkTimeout = 0;
        clientInfo.clear();
    }

    @Override
    public boolean isReadOnly() throws java.sql.SQLException {
        checkOpen();
        return readOnly;
    }

    @Override
    public void commit() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean isValid(int arg0) throws java.sql.SQLException {
        return !closed && arg0 >= 0;
    }

    @Override
    public void abort(java.util.concurrent.Executor arg0) throws java.sql.SQLException {
        if (arg0 != null) arg0.execute(() -> { try { close(); } catch (SQLException ignored) { } }); else close();
    }

    @Override
    public boolean isClosed() throws java.sql.SQLException {
        return closed;
    }

    @Override
    public java.sql.Statement createStatement(int arg0, int arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.Statement createStatement() throws java.sql.SQLException {
        checkOpen();
        BaseStatement statement = new BaseStatement(this);
        register(statement);
        return statement;
    }

    @Override
    public java.sql.Statement createStatement(int arg0, int arg1, int arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.DatabaseMetaData getMetaData() throws java.sql.SQLException {
        checkOpen();
        return new BaseDatabaseMetaData(this);
    }

    @Override
    public String nativeSQL(String arg0) throws java.sql.SQLException {
        checkOpen();
        return arg0;
    }

    @Override
    public void setAutoCommit(boolean arg0) throws java.sql.SQLException {
        checkOpen();
        autoCommit = arg0;
    }

    @Override
    public boolean getAutoCommit() throws java.sql.SQLException {
        checkOpen();
        return autoCommit;
    }

    @Override
    public void rollback(java.sql.Savepoint arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void rollback() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setCatalog(String arg0) throws java.sql.SQLException {
        checkOpen();
        catalog = arg0;
    }

    @Override
    public String getCatalog() throws java.sql.SQLException {
        checkOpen();
        return catalog;
    }

    @Override
    public void setSchema(String arg0) throws java.sql.SQLException {
        checkOpen();
        schema = arg0;
    }

    @Override
    public String getSchema() throws java.sql.SQLException {
        checkOpen();
        return schema;
    }

    @Override
    public void setTransactionIsolation(int arg0) throws java.sql.SQLException {
        checkOpen();
        if (arg0 != Connection.TRANSACTION_SERIALIZABLE) throw JdbcSupport.unsupported();
        transactionIsolation = arg0;
    }

    @Override
    public int getTransactionIsolation() throws java.sql.SQLException {
        checkOpen();
        return transactionIsolation;
    }

    @Override
    public void setHoldability(int arg0) throws java.sql.SQLException {
        checkOpen();
        if (arg0 != ResultSet.CLOSE_CURSORS_AT_COMMIT) throw JdbcSupport.unsupported();
        holdability = arg0;
    }

    @Override
    public int getHoldability() throws java.sql.SQLException {
        checkOpen();
        return holdability;
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
    public void setClientInfo(String arg0, String arg1) throws java.sql.SQLClientInfoException {
        if (closed) throw new SQLClientInfoException();
        if (arg1 == null) clientInfo.remove(arg0); else clientInfo.setProperty(arg0, arg1);
    }

    @Override
    public void setClientInfo(java.util.Properties arg0) throws java.sql.SQLClientInfoException {
        if (closed) throw new SQLClientInfoException();
        clientInfo.clear(); if (arg0 != null) clientInfo.putAll(arg0);
    }

    @Override
    public String getClientInfo(String arg0) throws java.sql.SQLException {
        checkOpen();
        return clientInfo.getProperty(arg0);
    }

    @Override
    public java.util.Properties getClientInfo() throws java.sql.SQLException {
        checkOpen();
        Properties copy = new Properties(); copy.putAll(clientInfo); return copy;
    }

    @Override
    public void setNetworkTimeout(java.util.concurrent.Executor arg0, int arg1) throws java.sql.SQLException {
        checkOpen();
        if (arg1 < 0) throw JdbcSupport.invalidArgument("network timeout must be >= 0");
        networkTimeout = arg1;
    }

    @Override
    public int getNetworkTimeout() throws java.sql.SQLException {
        checkOpen();
        return networkTimeout;
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(String arg0, int arg1, int arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(String arg0, String[] arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(String arg0, int[] arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(String arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(String arg0, int arg1, int arg2, int arg3) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(String arg0, int arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.CallableStatement prepareCall(String arg0, int arg1, int arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.CallableStatement prepareCall(String arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.CallableStatement prepareCall(String arg0, int arg1, int arg2, int arg3) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.util.Map<String, Class<?>> getTypeMap() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setTypeMap(java.util.Map<String, Class<?>> arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.Savepoint setSavepoint() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.Savepoint setSavepoint(String arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void releaseSavepoint(java.sql.Savepoint arg0) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.Clob createClob() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.Blob createBlob() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.NClob createNClob() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.SQLXML createSQLXML() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.Array createArrayOf(String arg0, Object[] arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public java.sql.Struct createStruct(String arg0, Object[] arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void beginRequest() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void endRequest() throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean setShardingKeyIfValid(java.sql.ShardingKey arg0, int arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public boolean setShardingKeyIfValid(java.sql.ShardingKey arg0, java.sql.ShardingKey arg1, int arg2) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setShardingKey(java.sql.ShardingKey arg0, java.sql.ShardingKey arg1) throws java.sql.SQLException {
        checkOpen();
        throw JdbcSupport.unsupported();
    }

    @Override
    public void setShardingKey(java.sql.ShardingKey arg0) throws java.sql.SQLException {
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
