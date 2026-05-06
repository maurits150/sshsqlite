package org.sshsqlite.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliProtocolClientTest {
    @TempDir Path tempDir;

    @Test
    void executesSelectDdlAndWritesThroughSqliteCli() throws Exception {
        try (Fixture fixture = open(false)) {
            SshSqliteConnection connection = fixture.connection();
            try (Statement statement = connection.createStatement()) {
                assertEquals(0, statement.executeUpdate("CREATE TABLE items(id INTEGER PRIMARY KEY, name TEXT)"));
                assertEquals(1, statement.executeUpdate("INSERT INTO items(name) VALUES ('alpha')"));
                assertEquals(1, statement.executeUpdate("UPDATE items SET name='beta' WHERE id=1"));
                assertTrue(statement.execute("SELECT id, name FROM items"));
                try (ResultSet rs = statement.getResultSet()) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getLong("id"));
                    assertEquals("beta", rs.getString("name"));
                    assertFalse(rs.next());
                }
                assertEquals(1, statement.executeUpdate("DELETE FROM items WHERE id=1"));
            }
        }
    }

    @Test
    void acceptsDbeaverSchemaQualifiedDropTable() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                assertEquals(0, statement.executeUpdate("CREATE TABLE NewTable(id INTEGER)"));
                assertEquals(0, statement.executeUpdate("DROP TABLE main.NewTable"));
            }
        }
    }

    @Test
    void commitsAndRollsBackTransactions() throws Exception {
        try (Fixture fixture = open(false)) {
            SshSqliteConnection connection = fixture.connection();
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE tx(v TEXT)");
            }
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO tx(v) VALUES ('rollback')");
            }
            connection.rollback();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT count(*) AS c FROM tx")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getLong("c"));
            }
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO tx(v) VALUES ('commit')");
            }
            connection.commit();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT count(*) AS c FROM tx")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getLong("c"));
            }
        }
    }

    @Test
    void rejectsWritesInReadonlyJdbcLayer() throws Exception {
        try (Fixture fixture = open(true)) {
            try (Statement statement = fixture.connection().createStatement()) {
                assertThrows(SQLException.class, () -> statement.executeUpdate("CREATE TABLE denied(v TEXT)"));
            }
        }
    }

    @Test
    void preparedStatementLiteralsEscapeTextAndBlob() throws Exception {
        try (Fixture fixture = open(false)) {
            SshSqliteConnection connection = fixture.connection();
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE safe(v TEXT, n INTEGER, flag INTEGER, r REAL, nil TEXT, b BLOB)");
            }
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO safe(v, n, flag, r, nil, b) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, "x'); DROP TABLE safe; --");
                ps.setLong(2, 42L);
                ps.setBoolean(3, true);
                ps.setDouble(4, 3.5d);
                ps.setNull(5, Types.VARCHAR);
                ps.setBytes(6, new byte[] {0, 1, 15, 16});
                assertEquals(1, ps.executeUpdate());
            }
            try (PreparedStatement ps = connection.prepareStatement("SELECT v, n, flag, r, nil, length(b) AS b_len FROM safe WHERE v = ?")) {
                ps.setString(1, "x'); DROP TABLE safe; --");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("x'); DROP TABLE safe; --", rs.getString("v"));
                    assertEquals(42L, rs.getLong("n"));
                    assertTrue(rs.getBoolean("flag"));
                    assertEquals(3.5d, rs.getDouble("r"));
                    assertEquals(null, rs.getString("nil"));
                    assertEquals(4, rs.getLong("b_len"));
                    assertFalse(rs.next());
                }
            }
        }
    }

    @Test
    void rendersCliLiteralsWithoutSqlInjection() throws Exception {
        String sql = CliProtocolClient.renderSql("SELECT ? AS v", List.of(Map.of("type", "text", "value", "a'b")));
        assertEquals("SELECT 'a''b' AS v", sql);
    }

    @Test
    void rendersNamedRepeatedAndNumberedPlaceholders() throws Exception {
        String named = CliProtocolClient.renderSql("SELECT :v AS a, :v AS b", List.of(Map.of("type", "text", "value", "same")));
        assertEquals("SELECT 'same' AS a, 'same' AS b", named);

        String numbered = CliProtocolClient.renderSql("SELECT ?2 AS a, ?1 AS b", List.of(
                Map.of("type", "integer", "value", 1),
                Map.of("type", "integer", "value", 2)));
        assertEquals("SELECT 2 AS a, 1 AS b", numbered);

        String mixed = CliProtocolClient.renderSql("SELECT ?1 AS a, ? AS b", List.of(
                Map.of("type", "integer", "value", 1),
                Map.of("type", "integer", "value", 2)));
        assertEquals("SELECT 1 AS a, 2 AS b", mixed);
    }

    @Test
    void rejectsMultipleStatementsBeforeExecution() throws Exception {
        try (Fixture fixture = open(false)) {
            SshSqliteConnection connection = fixture.connection();
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE multi(v TEXT)");
                assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO multi(v) VALUES ('a'); INSERT INTO multi(v) VALUES ('b')"));
            }
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT count(*) AS c FROM multi")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getLong("c"));
            }
        }
    }

    @Test
    void executeQueryRejectsNonRowStatements() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                assertThrows(SQLException.class, () -> statement.executeQuery("CREATE TABLE not_query(v TEXT)"));
            }
        }
    }

    @Test
    void timeoutKillsSqliteProcessSoLocksDoNotLinger() throws Exception {
        Path db = tempDir.resolve("locked.db");
        Process init = new ProcessBuilder("/usr/bin/sqlite3", db.toString(), "CREATE TABLE locked(v TEXT);").start();
        assertEquals(0, init.waitFor());

        Process locker = new ProcessBuilder("/usr/bin/sqlite3", db.toString()).start();
        try {
            locker.getOutputStream().write("BEGIN EXCLUSIVE; INSERT INTO locked(v) VALUES ('held');\n".getBytes(StandardCharsets.UTF_8));
            locker.getOutputStream().flush();
            Thread.sleep(250L);
            try (Fixture fixture = open(false, db, 100, 10_000)) {
                SQLException error = assertThrows(SQLException.class, () -> {
                    try (Statement statement = fixture.connection().createStatement()) {
                        statement.executeUpdate("INSERT INTO locked(v) VALUES ('blocked')");
                    }
                });
                assertTrue(error.getMessage().contains("broken") || error.getSQLState().equals("08006") || error.getSQLState().equals("HYT00"));
                assertTrue(fixture.process().waitFor(2, TimeUnit.SECONDS), "sqlite3 process should be killed on timeout");
            }
        } finally {
            locker.destroyForcibly();
        }
    }

    @Test
    void lockContentionSurfacesAsTransientError() throws Exception {
        Path db = tempDir.resolve("busy.db");
        Process init = new ProcessBuilder("/usr/bin/sqlite3", db.toString(), "CREATE TABLE busy(v TEXT);").start();
        assertEquals(0, init.waitFor());
        Process locker = new ProcessBuilder("/usr/bin/sqlite3", db.toString()).start();
        try {
            locker.getOutputStream().write("BEGIN EXCLUSIVE; INSERT INTO busy(v) VALUES ('held');\n".getBytes(StandardCharsets.UTF_8));
            locker.getOutputStream().flush();
            Thread.sleep(250L);
            try (Fixture fixture = open(false, db, 5_000, 1)) {
                SQLException error = assertThrows(SQLException.class, () -> {
                    try (Statement statement = fixture.connection().createStatement()) {
                        statement.executeUpdate("INSERT INTO busy(v) VALUES ('blocked')");
                    }
                });
                assertTrue(error instanceof SQLTransientException || "HYT00".equals(error.getSQLState()) || "08006".equals(error.getSQLState()), error.toString());
            }
        } finally {
            locker.destroyForcibly();
        }
    }

    @Test
    void oversizedCliResultAbortsSqliteProcess() throws Exception {
        try (Fixture fixture = open(false, tempDir.resolve("large.db"), 5_000, 1_000, 64)) {
            SQLException error = assertThrows(SQLException.class, () -> {
                try (Statement statement = fixture.connection().createStatement()) {
                    statement.executeQuery("SELECT printf('%0200d', 1) AS huge");
                }
            });
            assertEquals("08006", error.getSQLState(), error.toString());
            assertTrue(fixture.process().waitFor(2, TimeUnit.SECONDS), "sqlite3 process should be killed when result limit is exceeded");
        }
    }

    @Test
    void walModeReadWriteAndConcurrentWriterContentionUseCliBackend() throws Exception {
        Path db = tempDir.resolve("wal.db");
        try (Fixture fixture = open(false, db, 5_000, 1_000)) {
            try (Statement statement = fixture.connection().createStatement()) {
                statement.executeQuery("PRAGMA journal_mode=WAL").close();
                statement.executeUpdate("CREATE TABLE wal_items(id INTEGER PRIMARY KEY, v TEXT)");
                assertEquals(1, statement.executeUpdate("INSERT INTO wal_items(v) VALUES ('one')"));
            }
        }
        assertTrue(java.nio.file.Files.exists(db.resolveSibling(db.getFileName() + "-wal")) || java.nio.file.Files.exists(db));

        Process locker = new ProcessBuilder("/usr/bin/sqlite3", db.toString()).start();
        try {
            locker.getOutputStream().write("BEGIN IMMEDIATE; INSERT INTO wal_items(v) VALUES ('held');\n".getBytes(StandardCharsets.UTF_8));
            locker.getOutputStream().flush();
            Thread.sleep(250L);
            try (Fixture reader = open(false, db, 5_000, 1_000)) {
                try (Statement statement = reader.connection().createStatement(); ResultSet rs = statement.executeQuery("SELECT count(*) AS c FROM wal_items")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getLong("c"));
                }
            }
            try (Fixture writer = open(false, db, 5_000, 1)) {
                assertThrows(SQLException.class, () -> {
                    try (Statement statement = writer.connection().createStatement()) {
                        statement.executeUpdate("INSERT INTO wal_items(v) VALUES ('blocked')");
                    }
                });
            }
        } finally {
            locker.destroyForcibly();
        }
    }

    @Test
    void poolRollbackAndReuseUseCliBackend() throws Exception {
        Path db = tempDir.resolve("pool.db");
        try (SshSqliteDataSource dataSource = new SshSqliteDataSource()) {
            dataSource.setUrl("jdbc:sshsqlite://local" + db);
            dataSource.setProperty("helper.transport", "local");
            dataSource.setProperty("sqlite3.path", "/usr/bin/sqlite3");
            dataSource.setProperty("pool.enabled", "true");
            dataSource.setProperty("pool.maxSize", "1");

            try (java.sql.Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE pooled(v TEXT)");
            }
            try (java.sql.Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO pooled(v) VALUES ('rolled-back')");
                }
            }
            try (java.sql.Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT count(*) AS c FROM pooled")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getLong("c"));
            }
        }
    }

    private Fixture open(boolean readonly) throws Exception {
        Path db = tempDir.resolve(readonly ? "ro.db" : "rw.db");
        if (readonly) {
            Process init = new ProcessBuilder("/usr/bin/sqlite3", db.toString(), "PRAGMA user_version=1").start();
            assertEquals(0, init.waitFor());
        }
        return open(readonly, db, 10_000, 1_000);
    }

    private Fixture open(boolean readonly, Path db, int queryTimeoutMs, int busyTimeoutMs) throws Exception {
        return open(readonly, db, queryTimeoutMs, busyTimeoutMs, ConnectionConfig.DEFAULT_CLI_MAX_BUFFERED_RESULT_BYTES);
    }

    private Fixture open(boolean readonly, Path db, int queryTimeoutMs, int busyTimeoutMs, int maxBufferedResultBytes) throws Exception {
        Properties props = new Properties();
        props.setProperty("db.path", db.toString());
        props.setProperty("readonly", Boolean.toString(readonly));
        props.setProperty("sqlite3.path", "/usr/bin/sqlite3");
        props.setProperty("queryTimeoutMs", Integer.toString(queryTimeoutMs));
        props.setProperty("busyTimeoutMs", Integer.toString(busyTimeoutMs));
        props.setProperty("cli.maxBufferedResultBytes", Integer.toString(maxBufferedResultBytes));
        ConnectionConfig config = ConnectionConfig.parse("jdbc:sshsqlite://u@example.com/ignored.db", props);
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(config.sqlite3Path);
        command.add("-batch");
        if (readonly) {
            command.add("-readonly");
        }
        command.add(db.toString());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        BoundedCapture stderr = new BoundedCapture(process.getErrorStream(), config.stderrMaxBufferedBytes);
        var executor = Executors.newSingleThreadExecutor();
        executor.submit(stderr);
        CliProtocolClient client = new CliProtocolClient(process.getInputStream(), process.getOutputStream(), config, stderr, () -> {
            process.destroyForcibly();
            executor.shutdownNow();
        });
        SshSqliteConnection connection = new SshSqliteConnection(config, "jdbc:sshsqlite://u@example.com" + db, new TestTransport(process, client, stderr, executor));
        return new Fixture(connection, process);
    }

    private static final class Fixture implements AutoCloseable {
        private final SshSqliteConnection connection;
        private final Process process;

        Fixture(SshSqliteConnection connection, Process process) {
            this.connection = connection;
            this.process = process;
        }

        SshSqliteConnection connection() {
            return connection;
        }

        Process process() {
            return process;
        }

        @Override
        public void close() throws Exception {
            connection.close();
        }
    }

    private static final class TestTransport implements Transport {
        private final Process process;
        private final SqlClient client;
        private final BoundedCapture stderr;
        private final java.util.concurrent.ExecutorService executor;

        TestTransport(Process process, SqlClient client, BoundedCapture stderr, java.util.concurrent.ExecutorService executor) {
            this.process = process;
            this.client = client;
            this.stderr = stderr;
            this.executor = executor;
        }

        @Override public SqlClient protocol() { return client; }
        @Override public String diagnostics() { return stderr.text(); }
        @Override public void close() throws java.io.IOException {
            process.getOutputStream().close();
            process.destroy();
            executor.shutdownNow();
            client.closeReader();
        }
    }
}
