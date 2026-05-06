package org.sshsqlite.jdbc;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class SshSqliteConnection extends BaseConnection {
    private final ConnectionConfig config;
    private final String url;
    private final Transport transport;
    private boolean broken;
    private boolean transportClosed;
    private boolean autoCommit = true;
    private boolean activeTransaction;

    SshSqliteConnection(ConnectionConfig config, String url, Transport transport) {
        super();
        this.config = config;
        this.url = url;
        this.transport = transport;
    }

    static SshSqliteConnection open(String url, Properties info) throws SQLException {
        ConnectionConfig config;
        try {
            config = ConnectionConfig.parse(url, info);
        } catch (IllegalArgumentException e) {
            throw new SQLException(e.getMessage(), JdbcSupport.GENERAL_SQL_STATE, e);
        }
        Transport transport = null;
        try {
            transport = "local".equals(config.helperTransport) ? LocalProcessTransport.start(config) : SshTransport.connect(config);
            return new SshSqliteConnection(config, url, transport);
        } catch (Exception e) {
            if (transport != null) {
                try {
                    transport.close();
                } catch (Exception suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            throw new SQLException("Unable to open SSHSQLite connection", "08001", e);
        }
    }

    SqlClient protocol() throws SQLException {
        checkOpen();
        return transport.protocol();
    }

    int defaultFetchSize() {
        return config.fetchSize;
    }

    int defaultQueryTimeoutMs() {
        return config.queryTimeoutMs;
    }

    String sqliteVersion() throws SQLException { return protocol().sqliteVersion(); }

    String userName() { return config.sshUser; }

    String redactedUrl() { return "jdbc:sshsqlite://" + config.sshHost + "/<path-redacted>"; }

    String originalUrl() { return url; }

    boolean configuredReadOnly() { return config.readonly; }

    boolean brokenForPool() {
        return broken || transport.protocol().isBroken();
    }

    void ensureWritesAllowed() throws SQLException {
        checkOpen();
        if (config.readonly) {
            throw new SQLException("readonly connections cannot execute writes", JdbcSupport.GENERAL_SQL_STATE);
        }
    }

    void ensureTransactionForStatement() throws SQLException {
        checkOpen();
        if (!autoCommit && !activeTransaction) {
            try {
                transport.protocol().begin(config.transactionMode, config.queryTimeoutMs);
                activeTransaction = true;
            } catch (Exception e) {
                throw sqlException(e);
            }
        }
    }

    void transactionCompleted() {
        activeTransaction = false;
    }

    public String diagnosticsBundle(String operation) throws SQLException {
        SqlClient protocol = transport.protocol();
        ProtocolClient.HelperMetadata metadata = protocol.helperMetadata();
        java.util.Map<String, Object> bundle = new java.util.LinkedHashMap<>();
        bundle.put("schema", "sshsqlite-diagnostics-v1");
        bundle.put("driverVersion", "0.1.0-phase2");
        bundle.put("operation", operation == null || operation.isBlank() ? "unknown" : Redactor.sanitize(operation));
        bundle.put("jdbcUrl", Redactor.sanitize(redactedUrl()));
        bundle.put("connectionProperties", config.redactedProperties());
        bundle.put("connectionBroken", broken || protocol.isBroken());
        bundle.put("lastRequestId", protocol.lastRequestId());
        bundle.put("backendVersionHelloAck", metadata.helperVersion());
        bundle.put("backendVersionCommand", "not-collected");
        bundle.put("protocolVersion", metadata.protocolVersion());
        bundle.put("capabilities", metadata.capabilities());
        bundle.put("compileTimeCapabilities", metadata.compileTimeCapabilities());
        bundle.put("serverOs", metadata.os());
        bundle.put("serverArch", metadata.arch());
        bundle.put("sqliteVersion", metadata.sqliteVersion());
        bundle.put("sqliteCompileOptions", metadata.sqliteCompileOptions());
        bundle.put("sqlite3Path", "<path-redacted>");
        bundle.put("helperIntegrity", config.helperExpectedSha256 == null ? "not-configured" : "expected-sha256-configured");
        bundle.put("knownHostVerification", "configured");
        bundle.put("driverLogs", java.util.List.of());
        bundle.put("backendStderr", transport.diagnostics());
        try {
            return FrameCodec.JSON.writeValueAsString(bundle);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SQLException("Unable to generate SSHSQLite diagnostics bundle", JdbcSupport.GENERAL_SQL_STATE, e);
        }
    }

    void markBroken() {
        broken = true;
    }

    void cancelActiveStatement() throws SQLException {
        checkOpen();
        try {
            transport.protocol().cancelActive();
        } catch (Exception e) {
            throw sqlException(e);
        }
    }

    void resetForPoolReturn() throws SQLException {
        SQLException failure = null;
        try {
            closeRegisteredStatements();
        } catch (SQLException e) {
            failure = e;
        }
        if (failure == null && activeTransaction && !transport.protocol().isBroken()) {
            try {
                rollbackActiveTransaction();
            } catch (SQLException e) {
                failure = e;
            }
        }
        if (failure == null) {
            try {
                autoCommit = true;
                resetBaseMutableState();
                if (config.readonly != isReadOnly()) {
                    failure = JdbcSupport.invalidArgument("readonly mode reset failed");
                }
            } catch (SQLException e) {
                failure = e;
            }
        }
        if (failure == null && !isValid(Math.max(0, config.poolValidationTimeoutMs / 1000))) {
            failure = JdbcSupport.brokenConnection("Pooled SSHSQLite connection failed return validation", null);
        }
        if (failure != null) {
            broken = true;
            throw failure;
        }
    }

    SQLException sqlException(Exception e) {
        if (e instanceof SQLException) {
            return (SQLException) e;
        }
        if (transport.protocol().isBroken()) {
            broken = true;
        }
        return transport.protocol().toSqlException(e);
    }

    @Override
    protected void checkOpen() throws SQLException {
        super.checkOpen();
        if (broken || transport.protocol().isBroken()) {
            broken = true;
            throw JdbcSupport.brokenConnection("SSHSQLite backend connection is broken", null);
        }
    }

    @Override
    public Statement createStatement() throws SQLException {
        checkOpen();
        SshSqliteStatement statement = new SshSqliteStatement(this);
        register(statement);
        return statement;
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        checkOpen();
        SshSqlitePreparedStatement statement = new SshSqlitePreparedStatement(this, sql);
        register(statement);
        return statement;
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        checkOpen();
        SshSqlitePreparedStatement statement = new SshSqlitePreparedStatement(this, sql, SshSqliteStatement.returnGeneratedKeys(autoGeneratedKeys));
        register(statement);
        return statement;
    }

    @Override public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException { checkOpen(); throw JdbcSupport.unsupported(); }
    @Override public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException { checkOpen(); throw JdbcSupport.unsupported(); }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        checkOpen();
        return new SshSqliteDatabaseMetaData(this);
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        if (timeout < 0) {
            throw JdbcSupport.invalidArgument("timeout must be >= 0");
        }
        if (isClosed() || broken) {
            return false;
        }
        try {
            transport.protocol().ping();
            return true;
        } catch (Exception e) {
            broken = true;
            return false;
        }
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        checkOpen();
        if (this.autoCommit == autoCommit) {
            return;
        }
        if (!autoCommit) {
            ensureWritesAllowed();
        }
        if (autoCommit && activeTransaction) {
            commitActiveTransaction();
        }
        this.autoCommit = autoCommit;
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        checkOpen();
        return autoCommit;
    }

    @Override
    public void commit() throws SQLException {
        checkOpen();
        if (autoCommit) {
            throw new SQLException("commit is not allowed when autoCommit=true", JdbcSupport.GENERAL_SQL_STATE);
        }
        if (activeTransaction) {
            commitActiveTransaction();
        }
    }

    @Override
    public void rollback() throws SQLException {
        checkOpen();
        if (autoCommit) {
            throw new SQLException("rollback is not allowed when autoCommit=true", JdbcSupport.GENERAL_SQL_STATE);
        }
        if (activeTransaction) {
            rollbackActiveTransaction();
        }
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        checkOpen();
        if (readOnly != config.readonly) {
            throw JdbcSupport.invalidArgument("readonly mode is fixed when the SSHSQLite connection is opened");
        }
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        checkOpen();
        return config.readonly;
    }

    @Override
    public void close() throws SQLException {
        SQLException failure = null;
        if (!isClosed() && activeTransaction && !transport.protocol().isBroken()) {
            try {
                rollbackActiveTransaction();
            } catch (SQLException e) {
                failure = e;
            }
        }
        try {
            super.close();
        } catch (SQLException e) {
            failure = e;
        }
        if (!transportClosed) {
            transportClosed = true;
            try {
                transport.close();
            } catch (Exception e) {
                if (failure == null) {
                    failure = new SQLException("Unable to close SSHSQLite transport", JdbcSupport.GENERAL_SQL_STATE, e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void commitActiveTransaction() throws SQLException {
        ensureWritesAllowed();
        try {
            transport.protocol().commit(config.queryTimeoutMs);
            activeTransaction = false;
        } catch (Exception e) {
            if (transport.protocol().isBroken()) {
                broken = true;
            }
            throw sqlException(e);
        }
    }

    private void rollbackActiveTransaction() throws SQLException {
        ensureWritesAllowed();
        try {
            transport.protocol().rollback(config.queryTimeoutMs);
            activeTransaction = false;
        } catch (Exception e) {
            if (transport.protocol().isBroken()) {
                broken = true;
            }
            throw sqlException(e);
        }
    }
}
