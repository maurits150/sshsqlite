package org.sshsqlite.jdbc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("legacyHelper")
class SshSqliteDataSourcePoolingIntegrationTest {
    @TempDir Path tempDir;

    @Test
    void borrowReturnReusesHealthyPhysicalConnection() throws Exception {
        Path db = createDatabase("reuse.db");
        SshSqliteDataSource dataSource = dataSource(db, true);
        try {
            SshSqliteConnection first;
            try (Connection connection = dataSource.getConnection()) {
                first = connection.unwrap(SshSqliteConnection.class);
                assertEquals(3, count(connection));
            }

            try (Connection connection = dataSource.getConnection()) {
                assertSame(first, connection.unwrap(SshSqliteConnection.class));
                assertEquals(1, dataSource.physicalConnectionCount());
            }
        } finally {
            dataSource.close();
        }
    }

    @Test
    void pooledStatementsAndMetadataExposeLogicalConnection() throws Exception {
        Path db = createDatabase("logical.db");
        SshSqliteDataSource dataSource = dataSource(db, true);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertSame(connection, statement.getConnection());
            assertSame(connection, connection.getMetaData().getConnection());
            try (ResultSet tables = connection.getMetaData().getTables(null, null, "typed", null)) {
                assertNull(tables.getStatement());
                assertTrue(tables.next());
            }
            try (ResultSet columns = connection.getMetaData().getColumns(null, null, "typed", "id")) {
                assertNull(columns.getStatement());
                assertTrue(columns.next());
            }
            try (ResultSet rs = statement.executeQuery("SELECT id FROM typed ORDER BY id")) {
                assertSame(statement, rs.getStatement());
                assertSame(connection, rs.getStatement().getConnection());
            }
        } finally {
            dataSource.close();
        }
    }

    @Test
    void poolAllowsAtMostFiveBorrowedPhysicalConnections() throws Exception {
        Path db = createDatabase("max.db");
        SshSqliteDataSource dataSource = dataSource(db, true);
        dataSource.setPoolValidationTimeoutMs(25);
        List<Connection> borrowed = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                borrowed.add(dataSource.getConnection());
            }
            assertEquals(5, dataSource.physicalConnectionCount());
            assertThrows(SQLException.class, dataSource::getConnection);
        } finally {
            for (Connection connection : borrowed) {
                connection.close();
            }
            dataSource.close();
        }
    }

    @Test
    void brokenPhysicalConnectionIsEvictedOnReturn() throws Exception {
        Path db = createDatabase("broken.db");
        SshSqliteDataSource dataSource = dataSource(db, true);
        try {
            SshSqliteConnection broken;
            try (Connection connection = dataSource.getConnection()) {
                broken = connection.unwrap(SshSqliteConnection.class);
                broken.markBroken();
            }
            assertEquals(0, dataSource.physicalConnectionCount());

            try (Connection connection = dataSource.getConnection()) {
                assertNotSame(broken, connection.unwrap(SshSqliteConnection.class));
                assertEquals(1, dataSource.physicalConnectionCount());
            }
        } finally {
            dataSource.close();
        }
    }

    @Test
    void idleBorrowValidatesWithPingBeforeReuse() throws Exception {
        Path db = createDatabase("ping.db");
        SshSqliteDataSource dataSource = dataSource(db, true);
        try {
            SshSqliteConnection first;
            try (Connection connection = dataSource.getConnection()) {
                first = connection.unwrap(SshSqliteConnection.class);
            }
            try (Connection connection = dataSource.getConnection()) {
                assertSame(first, connection.unwrap(SshSqliteConnection.class));
                assertTrue(connection.isValid(1));
            }
        } finally {
            dataSource.close();
        }
    }

    @Test
    void activeTransactionRollsBackWhenLogicalConnectionReturns() throws Exception {
        Path db = createDatabase("rollback.db");
        SshSqliteDataSource dataSource = dataSource(db, false);
        try {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.executeUpdate("INSERT INTO typed(id, text_value) VALUES (10, 'rollback')");
            }
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT count(*) FROM typed WHERE id = 10")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
                assertTrue(connection.getAutoCommit());
            }
        } finally {
            dataSource.close();
        }
    }

    @Test
    void poolKeySeparatesReadonlyDatabasePathAndSecurityContext() throws Exception {
        Path db1 = createDatabase("key1.db");
        Path db2 = createDatabase("key2.db");
        SshSqliteDataSource dataSource = dataSource(db1, false);
        try {
            SshSqliteConnection readWrite;
            try (Connection connection = dataSource.getConnection()) {
                readWrite = connection.unwrap(SshSqliteConnection.class);
            }

            dataSource.setProperty("readonly", "true");
            try (Connection connection = dataSource.getConnection()) {
                assertNotSame(readWrite, connection.unwrap(SshSqliteConnection.class));
            }

            dataSource.setUrl(url(db2));
            dataSource.setProperty("helper.localAllowlist", allowlist(db2, true).toString());
            try (Connection connection = dataSource.getConnection()) {
                assertEquals(3, count(connection));
            }

            dataSource.setProperty("ssh.user", "other-user");
            try (Connection connection = dataSource.getConnection()) {
                assertEquals(3, count(connection));
            }

            assertEquals(4, dataSource.physicalConnectionCount());
        } finally {
            dataSource.close();
        }
    }

    @Test
    void fiveConcurrentReadConnectionsUsePoolSuccessfully() throws Exception {
        Path db = createDatabase("concurrent.db");
        SshSqliteDataSource dataSource = dataSource(db, true);
        var executor = Executors.newFixedThreadPool(5);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                tasks.add(() -> {
                    try (Connection connection = dataSource.getConnection()) {
                        return count(connection);
                    }
                });
            }
            for (var future : executor.invokeAll(tasks)) {
                assertEquals(3, future.get());
            }
            assertEquals(5, dataSource.physicalConnectionCount());
        } finally {
            executor.shutdownNow();
            dataSource.close();
        }
    }

    @Test
    void writeContentionSurfacesAsSqlExceptionNotPoolFailure() throws Exception {
        Path db = createDatabase("busy.db");
        SshSqliteDataSource dataSource = dataSource(db, false);
        try (Connection locker = dataSource.getConnection();
             Connection writer = dataSource.getConnection();
             Statement lockStatement = locker.createStatement();
             Statement writeStatement = writer.createStatement()) {
            locker.setAutoCommit(false);
            lockStatement.executeUpdate("UPDATE typed SET text_value = 'locked' WHERE id = 1");
            SQLException error = assertThrows(SQLException.class,
                    () -> writeStatement.executeUpdate("UPDATE typed SET text_value = 'blocked' WHERE id = 2"));
            String message = error.getMessage().toLowerCase(java.util.Locale.ROOT);
            assertTrue(!message.contains("pool") && !message.contains("broken"));
            locker.rollback();
        } finally {
            dataSource.close();
        }
    }

    private SshSqliteDataSource dataSource(Path db, boolean readonly) throws Exception {
        SshSqliteDataSource dataSource = new SshSqliteDataSource();
        dataSource.setUrl(url(db));
        dataSource.setPoolEnabled(true);
        dataSource.setProperty("readonly", Boolean.toString(readonly));
        if (!readonly) {
            dataSource.setProperty("writeBackupAcknowledged", "true");
            dataSource.setProperty("busyTimeoutMs", "100");
        }
        dataSource.setProperty("helper.transport", "local");
        dataSource.setProperty("helper.localPath", System.getProperty("sshsqlite.helperBinary"));
        dataSource.setProperty("helper.localAllowlist", allowlist(db, readonly).toString());
        dataSource.setProperty("helper.startupTimeoutMs", "10000");
        dataSource.setProperty("stderr.maxBufferedBytes", "4096");
        return dataSource;
    }

    private Path allowlist(Path db, boolean readonly) throws Exception {
        Path allowlist = tempDir.resolve(db.getFileName() + (readonly ? "-ro" : "-rw") + "-allowlist.json");
        Files.writeString(allowlist, "{\"version\":1,\"databases\":[{\"path\":\"" +
                jsonEscape(db.toRealPath().toString()) + "\",\"mode\":\"" +
                (readonly ? "readonly" : "readwrite") + "\"}]}\n");
        return allowlist;
    }

    private Path createDatabase(String name) throws Exception {
        Path db = tempDir.resolve(name);
        Process process = new ProcessBuilder("sqlite3", db.toString(),
                "CREATE TABLE typed(id INTEGER PRIMARY KEY, text_value TEXT);" +
                        "INSERT INTO typed VALUES (1, 'one'), (2, 'two'), (3, 'three');").start();
        assertEquals(0, process.waitFor());
        return db;
    }

    private String url(Path db) {
        return "jdbc:sshsqlite://local" + db;
    }

    private int count(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT count(*) FROM typed")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
