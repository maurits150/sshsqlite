package org.sshsqlite.jdbc;

import java.sql.DriverManager;
import java.sql.SQLException;

/** Minimal JDBC driver entry point for the SSHSQLite URL prefix. */
public final class SshSqliteDriver extends BaseDriver {
    static {
        SshTransport.forcePureJavaIoProvider();
        try {
            DriverManager.registerDriver(new SshSqliteDriver());
        } catch (SQLException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
