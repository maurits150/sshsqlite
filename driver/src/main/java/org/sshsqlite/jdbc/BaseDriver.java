package org.sshsqlite.jdbc;

import java.io.*;
import java.math.*;
import java.net.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class BaseDriver implements java.sql.Driver {
    public static final String URL_PREFIX = "jdbc:sshsqlite:";
    @Override public Connection connect(String url, Properties info) throws SQLException { if (!acceptsURL(url)) return null; return SshSqliteConnection.open(url, info); }
    @Override public boolean acceptsURL(String url) { return url != null && url.startsWith(URL_PREFIX); }
    @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
    @Override public int getMajorVersion() { return 0; }
    @Override public int getMinorVersion() { return 1; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException { throw JdbcSupport.unsupported(); }
}
