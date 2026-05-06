package org.sshsqlite.jdbc;

import java.io.*;
import java.math.*;
import java.net.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

final class JdbcSupport {
    static final String UNSUPPORTED_SQL_STATE = "0A000";
    static final String CLOSED_CONNECTION_SQL_STATE = "08003";
    static final String INVALID_STATE_SQL_STATE = "HY010";
    static final String GENERAL_SQL_STATE = "HY000";
    private JdbcSupport() { }
    static SQLFeatureNotSupportedException unsupported() { return new SQLFeatureNotSupportedException("JDBC feature is not supported by this adapter", UNSUPPORTED_SQL_STATE); }
    static SQLException closedConnection() { return new SQLException("Connection is closed", CLOSED_CONNECTION_SQL_STATE); }
    static SQLNonTransientConnectionException brokenConnection(String message, Throwable cause) { return new SQLNonTransientConnectionException(message, "08006", cause); }
    static SQLException closedObject() { return new SQLException("JDBC object is closed", INVALID_STATE_SQL_STATE); }
    static SQLException invalidState(String message) { return new SQLException(message, INVALID_STATE_SQL_STATE); }
    static SQLException invalidArgument(String message) { return new SQLException(message, GENERAL_SQL_STATE); }
    static <T> T unwrap(Object value, Class<T> iface) throws SQLException { if (iface == null) throw invalidArgument("Wrapper interface must not be null"); if (iface.isInstance(value)) return iface.cast(value); throw invalidArgument("Not a wrapper for " + iface.getName()); }
    static boolean isWrapperFor(Object value, Class<?> iface) throws SQLException { if (iface == null) throw invalidArgument("Wrapper interface must not be null"); return iface.isInstance(value); }
}
