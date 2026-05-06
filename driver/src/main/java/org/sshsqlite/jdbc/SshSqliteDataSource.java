package org.sshsqlite.jdbc;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/** Explicit DataSource entry point for optional max-5 SSHSQLite connection pooling. */
public final class SshSqliteDataSource implements DataSource, AutoCloseable {
    private String url;
    private final Properties properties = new Properties();
    private final Map<String, List<Entry>> pools = new HashMap<>();
    private PrintWriter logWriter;
    private int loginTimeout;
    private boolean shutdown;

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUrl() {
        return url;
    }

    public void setProperty(String name, String value) {
        if (value == null) {
            properties.remove(name);
        } else {
            properties.setProperty(name, value);
        }
    }

    public void setPoolEnabled(boolean enabled) {
        setProperty("pool.enabled", Boolean.toString(enabled));
    }

    public void setPoolMaxSize(int maxSize) {
        setProperty("pool.maxSize", Integer.toString(maxSize));
    }

    public void setPoolMinIdle(int minIdle) {
        setProperty("pool.minIdle", Integer.toString(minIdle));
    }

    public void setPoolIdleTimeoutMs(long idleTimeoutMs) {
        setProperty("pool.idleTimeoutMs", Long.toString(idleTimeoutMs));
    }

    public void setPoolValidationTimeoutMs(long validationTimeoutMs) {
        setProperty("pool.validationTimeoutMs", Long.toString(validationTimeoutMs));
    }

    public void setPoolMaxLifetimeMs(long maxLifetimeMs) {
        setProperty("pool.maxLifetimeMs", Long.toString(maxLifetimeMs));
    }

    @Override
    public Connection getConnection() throws SQLException {
        Properties copy = copyProperties();
        ConnectionConfig config = parseConfig(copy);
        if (!config.poolEnabled) {
            return SshSqliteConnection.open(url, copy);
        }
        return borrow(config, copy);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Properties copy = copyProperties();
        if (username != null) {
            copy.setProperty("ssh.user", username);
        }
        if (password != null) {
            copy.setProperty("ssh.password", password);
        }
        ConnectionConfig config = parseConfig(copy);
        if (!config.poolEnabled) {
            return SshSqliteConnection.open(url, copy);
        }
        return borrow(config, copy);
    }

    @Override
    public synchronized void close() throws SQLException {
        shutdown = true;
        SQLException failure = null;
        for (List<Entry> entries : pools.values()) {
            for (Entry entry : entries) {
                try {
                    entry.physical.close();
                } catch (SQLException e) {
                    if (failure == null) failure = e; else failure.addSuppressed(e);
                }
            }
        }
        pools.clear();
        notifyAll();
        if (failure != null) {
            throw failure;
        }
    }

    synchronized int physicalConnectionCount() {
        int count = 0;
        for (List<Entry> entries : pools.values()) {
            count += entries.size();
        }
        return count;
    }

    private Connection borrow(ConnectionConfig config, Properties openProperties) throws SQLException {
        String key = config.poolKeyHash();
        long deadline = System.currentTimeMillis() + config.poolValidationTimeoutMs;
        while (true) {
            Entry entry = takeIdle(key, config);
            if (entry != null) {
                if (validateBorrowed(entry, config)) {
                    return logicalConnection(entry);
                }
                evict(entry);
                continue;
            }

            synchronized (this) {
                if (shutdown) {
                    throw JdbcSupport.closedConnection();
                }
                List<Entry> entries = pools.computeIfAbsent(key, ignored -> new ArrayList<>());
                if (entries.size() < config.poolMaxSize) {
                    Entry reserved = new Entry(key, null, config);
                    entries.add(reserved);
                    try {
                        SshSqliteConnection physical = SshSqliteConnection.open(url, openProperties);
                        reserved.physical = physical;
                        reserved.inUse = true;
                        return logicalConnection(reserved);
                    } catch (SQLException e) {
                        entries.remove(reserved);
                        notifyAll();
                        throw e;
                    }
                }
                long waitMs = deadline - System.currentTimeMillis();
                if (waitMs <= 0) {
                    throw new SQLException("SSHSQLite pool exhausted", JdbcSupport.GENERAL_SQL_STATE);
                }
                try {
                    wait(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Interrupted while waiting for SSHSQLite pool", JdbcSupport.GENERAL_SQL_STATE, e);
                }
            }
        }
    }

    private synchronized Entry takeIdle(String key, ConnectionConfig config) throws SQLException {
        if (shutdown) {
            throw JdbcSupport.closedConnection();
        }
        List<Entry> entries = pools.get(key);
        if (entries == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        for (Entry entry : List.copyOf(entries)) {
            if (entry.inUse) {
                continue;
            }
            if (entry.physical.brokenForPool()
                    || (config.poolIdleTimeoutMs > 0 && now - entry.lastUsedAtMs > config.poolIdleTimeoutMs)
                    || (config.poolMaxLifetimeMs > 0 && now - entry.createdAtMs > config.poolMaxLifetimeMs)) {
                entries.remove(entry);
                entry.physical.close();
                continue;
            }
            entry.inUse = true;
            return entry;
        }
        return null;
    }

    private boolean validateBorrowed(Entry entry, ConnectionConfig config) {
        try {
            return !entry.physical.brokenForPool() && entry.physical.isValid(Math.max(0, config.poolValidationTimeoutMs / 1000));
        } catch (SQLException e) {
            entry.physical.markBroken();
            return false;
        }
    }

    private Connection logicalConnection(Entry entry) {
        InvocationHandler handler = new LogicalConnection(entry);
        return (Connection) Proxy.newProxyInstance(
                SshSqliteDataSource.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                handler);
    }

    private void release(Entry entry) throws SQLException {
        SQLException failure = null;
        try {
            if (entry.physical.brokenForPool()) {
                evict(entry);
                return;
            }
            entry.physical.resetForPoolReturn();
        } catch (SQLException e) {
            failure = e;
        }
        if (failure != null || entry.physical.brokenForPool()) {
            evict(entry);
            if (failure != null) {
                throw failure;
            }
            return;
        }
        synchronized (this) {
            entry.inUse = false;
            entry.lastUsedAtMs = System.currentTimeMillis();
            notifyAll();
        }
    }

    private synchronized void evict(Entry entry) {
        List<Entry> entries = pools.get(entry.key);
        if (entries != null) {
            entries.remove(entry);
            if (entries.isEmpty()) {
                pools.remove(entry.key);
            }
        }
        try {
            if (entry.physical != null) {
                entry.physical.close();
            }
        } catch (SQLException ignored) {
        }
        notifyAll();
    }

    private Properties copyProperties() {
        Properties copy = new Properties();
        synchronized (properties) {
            copy.putAll(properties);
        }
        return copy;
    }

    private ConnectionConfig parseConfig(Properties props) throws SQLException {
        if (url == null || url.isBlank()) {
            throw new SQLException("SshSqliteDataSource URL is required", JdbcSupport.GENERAL_SQL_STATE);
        }
        try {
            return ConnectionConfig.parse(url, props);
        } catch (IllegalArgumentException e) {
            throw new SQLException(e.getMessage(), JdbcSupport.GENERAL_SQL_STATE, e);
        }
    }

    @Override public PrintWriter getLogWriter() { return logWriter; }
    @Override public void setLogWriter(PrintWriter out) { logWriter = out; }
    @Override public void setLoginTimeout(int seconds) { loginTimeout = seconds; }
    @Override public int getLoginTimeout() { return loginTimeout; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw JdbcSupport.unsupported(); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { if (iface.isInstance(this)) return iface.cast(this); throw JdbcSupport.unsupported(); }
    @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }

    private final class LogicalConnection implements InvocationHandler {
        private final Entry entry;
        private boolean closed;

        private LogicalConnection(Entry entry) {
            this.entry = entry;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("close".equals(name) && method.getParameterCount() == 0) {
                if (!closed) {
                    closed = true;
                    release(entry);
                }
                return null;
            }
            if ("isClosed".equals(name) && method.getParameterCount() == 0) {
                return closed;
            }
            if ("unwrap".equals(name) && method.getParameterCount() == 1) {
                Class<?> iface = (Class<?>) args[0];
                if (iface.isInstance(entry.physical)) {
                    return iface.cast(entry.physical);
                }
            }
            if ("isWrapperFor".equals(name) && method.getParameterCount() == 1) {
                return ((Class<?>) args[0]).isInstance(entry.physical);
            }
            if (closed) {
                throw JdbcSupport.closedConnection();
            }
            try {
                return wrapJdbcReturn(proxy, method.invoke(entry.physical, args));
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private Object wrapJdbcReturn(Object logicalConnection, Object value) {
            if (value instanceof PreparedStatement) {
                return proxyWithLogicalConnection(value, logicalConnection, PreparedStatement.class);
            }
            if (value instanceof Statement) {
                return proxyWithLogicalConnection(value, logicalConnection, Statement.class);
            }
            if (value instanceof DatabaseMetaData) {
                return proxyWithLogicalConnection(value, logicalConnection, DatabaseMetaData.class);
            }
            return value;
        }

        private Object proxyWithLogicalConnection(Object target, Object logicalConnection, Class<?> iface) {
            return Proxy.newProxyInstance(
                    SshSqliteDataSource.class.getClassLoader(),
                    new Class<?>[]{iface},
                    (proxy, method, args) -> {
                        if ("getConnection".equals(method.getName()) && method.getParameterCount() == 0) {
                            return logicalConnection;
                        }
                        try {
                            Object owner = iface == DatabaseMetaData.class ? null : proxy;
                            return wrapJdbcReturn(logicalConnection, owner, method.invoke(target, args));
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        private Object wrapJdbcReturn(Object logicalConnection, Object logicalStatement, Object value) {
            if (value instanceof ResultSet) {
                return Proxy.newProxyInstance(
                        SshSqliteDataSource.class.getClassLoader(),
                        new Class<?>[]{ResultSet.class},
                        (proxy, method, args) -> {
                            try {
                                if ("getStatement".equals(method.getName()) && method.getParameterCount() == 0) {
                                    return logicalStatement == null ? method.invoke(value, args) : logicalStatement;
                                }
                                return method.invoke(value, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
            }
            return value;
        }
    }

    private static final class Entry {
        final String key;
        final ConnectionConfig config;
        final long createdAtMs = System.currentTimeMillis();
        SshSqliteConnection physical;
        boolean inUse;
        long lastUsedAtMs = createdAtMs;

        Entry(String key, SshSqliteConnection physical, ConnectionConfig config) {
            this.key = key;
            this.physical = physical;
            this.config = config;
        }
    }
}
