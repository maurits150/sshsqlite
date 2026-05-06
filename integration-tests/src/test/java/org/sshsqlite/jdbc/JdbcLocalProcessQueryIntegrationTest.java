package org.sshsqlite.jdbc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("legacyHelper")
class JdbcLocalProcessQueryIntegrationTest {
    @TempDir Path tempDir;

    @Test
    void jdbcQueryPathStreamsRowsAndMapsTypes() throws Exception {
        Path db = createDatabase();

        try (Connection connection = DriverManager.getConnection(url(db), properties(db));
             Statement statement = connection.createStatement()) {
            statement.setFetchSize(2);
            statement.setMaxRows(3);

            assertTrue(statement.execute("SELECT id, real_value, text_value, blob_value, null_value FROM typed ORDER BY id"));
            assertEquals(-1, statement.getUpdateCount());
            assertEquals(-1L, statement.getLargeUpdateCount());
            ResultSet resultSet = statement.getResultSet();
            assertNotNull(resultSet);
            assertEquals(5, resultSet.getMetaData().getColumnCount());

            assertTrue(resultSet.next());
            assertEquals(1, resultSet.getInt("id"));
            assertFalse(resultSet.wasNull());
            assertEquals(1L, resultSet.getObject(1));
            assertEquals(1.5d, resultSet.getDouble("real_value"));
            assertEquals("hello", resultSet.getString("text_value"));
            assertArrayEquals(new byte[]{0, 1, 2}, resultSet.getBytes("blob_value"));
            assertEquals(null, resultSet.getObject("null_value"));
            assertTrue(resultSet.wasNull());

            assertTrue(resultSet.next());
            assertEquals(2, resultSet.getLong(1));
            assertTrue(resultSet.next());
            assertEquals(3, resultSet.getLong(1));
            assertFalse(resultSet.next());
            assertFalse(statement.getMoreResults());
            assertEquals(-1, statement.getUpdateCount());
        }
    }

    @Test
    void closingResultSetFinalizesCursorAndConnectionClosesChildren() throws Exception {
        Path db = createDatabase();

        try (Connection connection = DriverManager.getConnection(url(db), properties(db))) {
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT id FROM typed ORDER BY id");
            assertTrue(resultSet.next());
            resultSet.close();

            try (ResultSet next = statement.executeQuery("SELECT count(*) AS c FROM typed")) {
                assertTrue(next.next());
                assertEquals(5, next.getInt("c"));
            }

            ResultSet openResult = statement.executeQuery("SELECT id FROM typed");
            connection.close();
            assertTrue(statement.isClosed());
            assertTrue(openResult.isClosed());
            assertTrue(connection.isClosed());
        }
    }

    @Test
    void helperCrashMarksConnectionBroken() throws Exception {
        Path db = createDatabase();
        Path script = tempDir.resolve("crash-helper.sh");
        Files.writeString(script, "#!/bin/sh\nexec \"" + System.getProperty("java.home") + "/bin/java\" -cp \"" + System.getProperty("java.class.path") + "\" org.sshsqlite.jdbc.FixtureHelper crashAfterOpen\n");
        script.toFile().setExecutable(true);

        Properties props = properties(db);
        props.setProperty("helper.localPath", script.toString());

        try (Connection connection = DriverManager.getConnection(url(db), props);
             Statement statement = connection.createStatement()) {
            SQLException error = assertThrows(SQLException.class, () -> statement.executeQuery("SELECT 1"));
            assertInstanceOf(SQLNonTransientConnectionException.class, error);
            assertThrows(SQLNonTransientConnectionException.class, () -> statement.executeQuery("SELECT 1"));
        }
    }

    @Test
    void readOnlyWriteMethodsRemainUnsupported() throws Exception {
        Path db = createDatabase();
        try (Connection connection = DriverManager.getConnection(url(db), properties(db));
             Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE typed SET text_value = 'x'"));
            assertThrows(SQLException.class, () -> statement.executeLargeUpdate("UPDATE typed SET text_value = 'x'"));
            assertThrows(SQLException.class, () -> connection.setAutoCommit(false));
        }
    }

    @Test
    void readWriteOpenRequiresBackupAcknowledgement() throws Exception {
        Path db = createDatabase();
        Properties props = properties(db, false);
        props.setProperty("readonly", "false");

        SQLException error = assertThrows(SQLException.class, () -> DriverManager.getConnection(url(db), props));
        assertTrue(error.getMessage().contains("Unable to open SSHSQLite connection"));
    }

    @Test
    void autocommitInsertUpdateDeleteAndExecuteUpdateState() throws Exception {
        Path db = createDatabase();
        try (Connection connection = DriverManager.getConnection(url(db), writeProperties(db));
             Statement statement = connection.createStatement()) {
            assertTrue(connection.getAutoCommit());
            assertEquals(1, statement.executeUpdate("INSERT INTO typed(id, real_value, text_value) VALUES (10, 10.5, 'ten')"));
            assertEquals(1, statement.getUpdateCount());
            assertFalse(statement.execute("UPDATE typed SET text_value = 'TEN' WHERE id = 10"));
            assertEquals(1, statement.getUpdateCount());
            assertEquals(1L, statement.executeLargeUpdate("DELETE FROM typed WHERE id = 10"));
            assertEquals(0, countRows(db, "SELECT count(*) FROM typed WHERE id = 10"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("SELECT id FROM typed"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE typed SET text_value = 'x' WHERE id = 1 RETURNING id"));
        }
    }

    @Test
    void statementGeneratedKeysReturnLastInsertRowidWhenRequested() throws Exception {
        Path db = createDatabase();
        try (Connection connection = DriverManager.getConnection(url(db), writeProperties(db));
             Statement statement = connection.createStatement()) {
            assertFalse(statement.execute("INSERT INTO typed(text_value) VALUES ('generated')", Statement.RETURN_GENERATED_KEYS));

            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertEquals(1, keys.getMetaData().getColumnCount());
                assertEquals("GENERATED_KEY", keys.getMetaData().getColumnName(1));
                assertTrue(keys.next());
                assertEquals(6L, keys.getLong(1));
                assertFalse(keys.next());
            }

            assertEquals(1, statement.executeUpdate("INSERT INTO typed(text_value) VALUES ('no keys')", Statement.NO_GENERATED_KEYS));
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertFalse(keys.next());
            }

            assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO typed(text_value) VALUES ('named')", new String[]{"id"}));
            assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO typed(text_value) VALUES ('indexed')", new int[]{1}));
        }
    }

    @Test
    void manualTransactionCommitAndRollback() throws Exception {
        Path db = createDatabase();
        try (Connection connection = DriverManager.getConnection(url(db), writeProperties(db));
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            assertEquals(1, statement.executeUpdate("INSERT INTO typed(id, text_value) VALUES (20, 'commit')"));
            connection.commit();
            assertEquals(1, countRows(db, "SELECT count(*) FROM typed WHERE id = 20"));

            assertEquals(1, statement.executeUpdate("INSERT INTO typed(id, text_value) VALUES (21, 'rollback')"));
            connection.rollback();
            assertEquals(0, countRows(db, "SELECT count(*) FROM typed WHERE id = 21"));
        }
    }

    @Test
    void commitAndRollbackFailInAutocommitMode() throws Exception {
        Path db = createDatabase();
        try (Connection connection = DriverManager.getConnection(url(db), writeProperties(db))) {
            assertTrue(connection.getAutoCommit());
            assertThrows(SQLException.class, connection::commit);
            assertThrows(SQLException.class, connection::rollback);
        }
    }

    @Test
    void preparedStatementWritesUseBoundParameters() throws Exception {
        Path db = createDatabase();
        try (Connection connection = DriverManager.getConnection(url(db), writeProperties(db));
             PreparedStatement insert = connection.prepareStatement("INSERT INTO typed(id, text_value) VALUES (?, ?)");
             PreparedStatement update = connection.prepareStatement("UPDATE typed SET text_value = ? WHERE id = ?");
             PreparedStatement delete = connection.prepareStatement("DELETE FROM typed WHERE id = ?")) {
            insert.setInt(1, 30);
            insert.setString(2, "prepared");
            assertEquals(1, insert.executeUpdate());
            update.setString(1, "changed");
            update.setInt(2, 30);
            assertFalse(update.execute());
            assertEquals(1, update.getUpdateCount());
            delete.setInt(1, 30);
            assertEquals(1, delete.executeLargeUpdate());
            assertEquals(0, countRows(db, "SELECT count(*) FROM typed WHERE id = 30"));
        }
    }

    @Test
    void preparedStatementGeneratedKeysReturnLastInsertRowidWhenRequested() throws Exception {
        Path db = createDatabase();
        try (Connection connection = DriverManager.getConnection(url(db), writeProperties(db));
             PreparedStatement insert = connection.prepareStatement("INSERT INTO typed(text_value) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, "prepared generated");
            assertEquals(1, insert.executeUpdate());

            try (ResultSet keys = insert.getGeneratedKeys()) {
                assertTrue(keys.next());
                assertEquals(6L, keys.getLong("GENERATED_KEY"));
                assertFalse(keys.next());
            }
        }
        try (Connection connection = DriverManager.getConnection(url(db), writeProperties(db))) {
            assertThrows(SQLException.class, () -> connection.prepareStatement("INSERT INTO typed(text_value) VALUES (?)", new String[]{"id"}));
            assertThrows(SQLException.class, () -> connection.prepareStatement("INSERT INTO typed(text_value) VALUES (?)", new int[]{1}));
        }
    }

    @Test
    void closeRollsBackActiveTransaction() throws Exception {
        Path db = createDatabase();
        Connection connection = DriverManager.getConnection(url(db), writeProperties(db));
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO typed(id, text_value) VALUES (40, 'close')");
        }
        connection.close();
        assertEquals(0, countRows(db, "SELECT count(*) FROM typed WHERE id = 40"));
    }

    @Test
    void busyWriteSurfacesSQLException() throws Exception {
        Path db = createDatabase();
        try (Connection locker = DriverManager.getConnection(url(db), writeProperties(db));
             Connection writer = DriverManager.getConnection(url(db), writeProperties(db));
             Statement lockStatement = locker.createStatement();
             Statement writeStatement = writer.createStatement()) {
            locker.setAutoCommit(false);
            lockStatement.executeUpdate("UPDATE typed SET text_value = 'locked' WHERE id = 1");
            assertThrows(SQLException.class, () -> writeStatement.executeUpdate("UPDATE typed SET text_value = 'blocked' WHERE id = 2"));
            locker.rollback();
        }
    }

    @Test
    void preparedStatementSetterMappingsAndRepeatedExecution() throws Exception {
        Path db = createDatabase();
        try (Connection connection = DriverManager.getConnection(url(db), writeProperties(db));
             PreparedStatement ps = connection.prepareStatement("SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?")) {
            ps.setNull(1, Types.INTEGER);
            ps.setString(2, "text");
            ps.setInt(3, 3);
            ps.setLong(4, 4L);
            ps.setDouble(5, 5.5d);
            ps.setFloat(6, 6.5f);
            ps.setBoolean(7, true);
            ps.setBytes(8, new byte[]{9, 10});
            ps.setBigDecimal(9, new BigDecimal("123.45"));
            ps.setDate(10, Date.valueOf("2026-05-05"));
            ps.setTime(11, Time.valueOf("12:34:56"));
            ps.setTimestamp(12, Timestamp.valueOf("2026-05-05 12:34:56.789"));
            ps.setObject(13, false);

            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(null, rs.getObject(1));
                assertEquals("text", rs.getString(2));
                assertEquals(3, rs.getInt(3));
                assertEquals(4L, rs.getLong(4));
                assertEquals(5.5d, rs.getDouble(5));
                assertEquals(6.5d, rs.getDouble(6), 0.001d);
                assertEquals(1, rs.getInt(7));
                assertArrayEquals(new byte[]{9, 10}, rs.getBytes(8));
                assertEquals("123.45", rs.getString(9));
                assertEquals("2026-05-05", rs.getString(10));
                assertEquals("12:34:56", rs.getString(11));
                assertTrue(rs.getString(12).startsWith("2026-05-05 12:34:56.789"));
                assertEquals(0, rs.getInt(13));
            }

            ps.setString(2, "changed");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("changed", rs.getString(2));
            }
        }
    }

    @Test
    void preparedStatementValidatesMissingAndInvalidParameters() throws Exception {
        Path db = createDatabase();
        try (Connection connection = DriverManager.getConnection(url(db), writeProperties(db));
             PreparedStatement ps = connection.prepareStatement("SELECT ?")) {
            assertThrows(SQLException.class, ps::executeQuery);
            assertThrows(SQLException.class, () -> ps.setString(0, "bad"));
            assertThrows(SQLException.class, () -> ps.setString(2, "bad"));
            ps.setString(1, "ok");
            ps.clearParameters();
            assertThrows(SQLException.class, ps::executeQuery);
        }
        try (Connection connection = DriverManager.getConnection(url(db), properties(db))) {
            assertThrows(SQLException.class, () -> connection.prepareStatement("SELECT ?10"));
        }
    }

    @Test
    void preparedStatementBindsValuesWithoutInterpolatingSql() throws Exception {
        Path db = createDatabase();
        try (Connection connection = DriverManager.getConnection(url(db), properties(db));
             PreparedStatement ps = connection.prepareStatement("SELECT count(*) FROM typed WHERE text_value = ?")) {
            ps.setString(1, "hello' OR 1=1 --");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    void preparedStatementBindsNamedPlaceholdersByIndexAndRepeatedNames() throws Exception {
        Path db = createDatabase();
        try (Connection connection = DriverManager.getConnection(url(db), properties(db));
             PreparedStatement ps = connection.prepareStatement("SELECT :x, :x, @y, $z")) {
            ps.setString(1, "same");
            ps.setInt(2, 2);
            ps.setLong(3, 3L);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("same", rs.getString(1));
                assertEquals("same", rs.getString(2));
                assertEquals(2, rs.getInt(3));
                assertEquals(3L, rs.getLong(4));
            }
        }
    }

    @Test
    void preparedStatementSqlStringMethodsAndUpdatesRemainUnsupported() throws Exception {
        Path db = createDatabase();
        try (Connection connection = DriverManager.getConnection(url(db), properties(db));
             PreparedStatement ps = connection.prepareStatement("SELECT 1")) {
            assertThrows(SQLException.class, () -> ps.executeQuery("SELECT 2"));
            assertThrows(SQLException.class, () -> ps.execute("SELECT 2"));
            assertThrows(SQLException.class, ps::executeUpdate);
        }
    }

    @Test
    void queryTimeoutInterruptsCpuHeavyQueryAndConnectionRemainsUsable() throws Exception {
        Path db = createDatabase();
        try (Connection connection = DriverManager.getConnection(url(db), writeProperties(db));
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(1);
            ResultSet resultSet = statement.executeQuery(cpuHeavyQuery());

            assertThrows(SQLException.class, resultSet::next);

            try (Statement check = connection.createStatement();
                 ResultSet rs = check.executeQuery("SELECT 1")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void statementCancelInterruptsActiveFetchAndConnectionRemainsUsable() throws Exception {
        Path db = createDatabase();
        try (Connection connection = DriverManager.getConnection(url(db), writeProperties(db));
             Statement statement = connection.createStatement()) {
            ResultSet resultSet = statement.executeQuery(cpuHeavyQuery());
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> next = executor.submit(() -> {
                    try {
                        resultSet.next();
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
                Thread.sleep(100);
                statement.cancel();
                assertThrows(Exception.class, () -> next.get(5, TimeUnit.SECONDS));
            } finally {
                executor.shutdownNow();
            }

            try (Statement check = connection.createStatement();
                 ResultSet rs = check.executeQuery("SELECT 1")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void cancelledWriteMarksConnectionBrokenForUnknownOutcome() throws Exception {
        Path db = createDatabase();
        try (Connection connection = DriverManager.getConnection(url(db), writeProperties(db));
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(1);
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE typed SET text_value = (" + cpuHeavyQuery() + ") WHERE id = 1"));
            assertFalse(connection.isValid(1));
        }
    }

    private Path createDatabase() throws Exception {
        Path db = tempDir.resolve("fixture.db");
        Process process = new ProcessBuilder("sqlite3", db.toString(), "CREATE TABLE typed(id INTEGER PRIMARY KEY, real_value REAL, text_value TEXT, blob_value BLOB, null_value TEXT);" +
                "INSERT INTO typed VALUES (1, 1.5, 'hello', X'000102', NULL), (2, 2.5, 'two', X'03', NULL), (3, 3.5, 'three', X'04', NULL), (4, 4.5, 'four', X'05', NULL), (5, 5.5, 'five', X'06', NULL);").start();
        assertEquals(0, process.waitFor());
        return db;
    }

    private String cpuHeavyQuery() {
        return "SELECT count(*) FROM typed a, typed b, typed c, typed d, typed e, typed f, typed g, typed h, typed i, typed j, typed k, typed l, typed m, typed n, typed o, typed p";
    }

    private String url(Path db) {
        return "jdbc:sshsqlite://local" + db;
    }

    private Properties properties(Path db) throws Exception {
        return properties(db, true);
    }

    private Properties writeProperties(Path db) throws Exception {
        Properties props = properties(db, false);
        props.setProperty("readonly", "false");
        props.setProperty("writeBackupAcknowledged", "true");
        props.setProperty("busyTimeoutMs", "100");
        return props;
    }

    private Properties properties(Path db, boolean readonly) throws Exception {
        Path allowlist = tempDir.resolve("allowlist.json");
        Files.writeString(allowlist, "{\"version\":1,\"databases\":[{\"path\":\"" +
                db.toRealPath().toString().replace("\\", "\\\\").replace("\"", "\\\"") +
                "\",\"mode\":\"" + (readonly ? "readonly" : "readwrite") + "\"}]}\n");
        Properties props = new Properties();
        props.setProperty("helper.transport", "local");
        props.setProperty("helper.localPath", System.getProperty("sshsqlite.helperBinary"));
        props.setProperty("helper.localAllowlist", allowlist.toString());
        props.setProperty("helper.startupTimeoutMs", "10000");
        props.setProperty("stderr.maxBufferedBytes", "4096");
        return props;
    }

    private int countRows(Path db, String sql) throws Exception {
        Process process = new ProcessBuilder("sqlite3", db.toString(), sql).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
        return Integer.parseInt(output.trim());
    }
}
