package org.sshsqlite.jdbc;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class SshSqliteConnection extends BaseConnection {
    private final ConnectionConfig config;
    private final String url;
    private final Transport transport;
    private boolean broken;
    private boolean transportClosed;
    private boolean autoCommit = true;
    private boolean activeTransaction;
    private int explicitSavepointDepth;
    private boolean transactionStartedBySavepoint;

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
                explicitSavepointDepth = 0;
                transactionStartedBySavepoint = false;
            } catch (Exception e) {
                throw sqlException(e);
            }
        }
    }

    void ensureTransactionForStatement(String sql) throws SQLException {
        checkOpen();
        if (!autoCommit && !CliProtocolClient.containsExplicitTransactionControl(CliProtocolClient.splitSqlStatements(sql))) {
            ensureTransactionForStatement();
        }
    }

    void transactionCompleted() {
        activeTransaction = false;
        explicitSavepointDepth = 0;
        transactionStartedBySavepoint = false;
    }

    void syncExplicitTransactionState(String sql) {
        for (String statement : CliProtocolClient.splitSqlStatements(sql)) {
            applyTransactionControl(statement);
        }
    }

    void recoverFailedExplicitTransactionScript(String sql, Exception failure) {
        List<String> statements = CliProtocolClient.splitSqlStatements(sql);
        if (statements.size() <= 1 || !CliProtocolClient.containsExplicitTransactionControl(statements)) {
            return;
        }
        int completedStatements = failure instanceof CliProtocolClient.ScriptExecutionException
                ? ((CliProtocolClient.ScriptExecutionException) failure).completedStatements()
                : 0;
        boolean hadActiveTransaction = activeTransaction;
        int initialSavepointDepth = explicitSavepointDepth;
        String scriptSavepoint = firstOpenedSavepoint(statements, completedStatements, initialSavepointDepth);
        for (int i = 0; i < completedStatements && i < statements.size(); i++) {
            applyTransactionControl(statements.get(i));
        }
        if (scriptSavepoint != null) {
            rollbackAndReleaseSavepoint(scriptSavepoint);
            return;
        }
        if (activeTransaction && !hadActiveTransaction) {
            rollbackFailedScriptTransaction();
        }
    }

    private void applyTransactionControl(String statement) {
        switch (CliProtocolClient.transactionControl(statement)) {
            case BEGIN:
                activeTransaction = true;
                explicitSavepointDepth = 0;
                transactionStartedBySavepoint = false;
                break;
            case COMMIT:
            case ROLLBACK:
                transactionCompleted();
                break;
            case SAVEPOINT:
                if (!activeTransaction) {
                    activeTransaction = true;
                    transactionStartedBySavepoint = true;
                }
                explicitSavepointDepth++;
                break;
            case RELEASE:
                if (explicitSavepointDepth > 0) {
                    explicitSavepointDepth--;
                }
                if (explicitSavepointDepth == 0 && transactionStartedBySavepoint) {
                    transactionCompleted();
                }
                break;
            case ROLLBACK_TO:
            case NONE:
                break;
        }
    }

    private String firstOpenedSavepoint(List<String> statements, int completedStatements, int initialSavepointDepth) {
        int depth = initialSavepointDepth;
        List<String> scriptSavepoints = new ArrayList<>();
        for (int i = 0; i < completedStatements && i < statements.size(); i++) {
            String statement = statements.get(i);
            CliProtocolClient.TransactionControl control = CliProtocolClient.transactionControl(statement);
            if (control == CliProtocolClient.TransactionControl.SAVEPOINT) {
                depth++;
                if (depth > initialSavepointDepth) {
                    scriptSavepoints.add(CliProtocolClient.transactionControlName(statement));
                }
            } else if (control == CliProtocolClient.TransactionControl.RELEASE && depth > initialSavepointDepth) {
                depth--;
                if (!scriptSavepoints.isEmpty()) {
                    scriptSavepoints.remove(scriptSavepoints.size() - 1);
                }
            } else if (control == CliProtocolClient.TransactionControl.ROLLBACK || control == CliProtocolClient.TransactionControl.COMMIT) {
                depth = 0;
                scriptSavepoints.clear();
            }
        }
        return scriptSavepoints.isEmpty() ? null : scriptSavepoints.get(0);
    }

    private void rollbackAndReleaseSavepoint(String savepoint) {
        if (savepoint == null || savepoint.isBlank()) {
            broken = true;
            return;
        }
        try {
            transport.protocol().exec("ROLLBACK TO " + savepoint + "; RELEASE " + savepoint, null, config.queryTimeoutMs);
            if (explicitSavepointDepth > 0) {
                explicitSavepointDepth--;
            }
            if (explicitSavepointDepth == 0 && transactionStartedBySavepoint) {
                transactionCompleted();
            }
        } catch (Exception cleanupFailure) {
            broken = true;
        }
    }

    private void rollbackFailedScriptTransaction() {
        try {
            transport.protocol().rollback(config.queryTimeoutMs);
            transactionCompleted();
        } catch (Exception rollbackFailure) {
            broken = true;
        }
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
        var executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "sshsqlite-is-valid");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Callable<Boolean> ping = () -> {
                transport.protocol().ping();
                return true;
            };
            return timeout == 0 ? executor.submit(ping).get() : executor.submit(ping).get(timeout, TimeUnit.SECONDS);
        } catch (Exception e) {
            broken = true;
            return false;
        } finally {
            executor.shutdownNow();
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
    public void abort(java.util.concurrent.Executor executor) throws SQLException {
        if (executor == null) {
            throw JdbcSupport.invalidArgument("executor must not be null");
        }
        broken = true;
        executor.execute(() -> {
            try {
                close();
            } catch (SQLException ignored) {
            }
        });
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
            transactionCompleted();
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
            transactionCompleted();
        } catch (Exception e) {
            if (transport.protocol().isBroken()) {
                broken = true;
            }
            throw sqlException(e);
        }
    }
}
