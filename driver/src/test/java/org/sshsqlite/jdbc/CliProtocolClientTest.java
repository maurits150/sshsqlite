package org.sshsqlite.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTransientException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
    void oldSqliteDropColumnReportsVersionRequirement() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                assumeTrue(!sqliteVersionAtLeast(fixture.connection().sqliteVersion(), "3.35.0"), "local sqlite3 supports DROP COLUMN");
                statement.executeUpdate("CREATE TABLE drop_column_old(a INTEGER, b INTEGER)");
                SQLException error = assertThrows(SQLException.class, () -> statement.executeUpdate("ALTER TABLE main.drop_column_old DROP COLUMN b"));
                assertTrue(error instanceof SQLFeatureNotSupportedException, error.toString());
                assertTrue(error.getMessage().contains("requires SQLite 3.35.0"), error.getMessage());
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
    void explicitTransactionSqlSynchronizesJdbcStateWhenAutoCommitFalse() throws Exception {
        try (Fixture fixture = open(false)) {
            SshSqliteConnection connection = fixture.connection();
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE explicit_tx(v TEXT)");
            }

            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                assertEquals(0, statement.executeUpdate("BEGIN"));
                assertEquals(1, statement.executeUpdate("INSERT INTO explicit_tx(v) VALUES ('committed')"));
                assertEquals(0, statement.executeUpdate("COMMIT"));
                connection.commit();

                assertEquals(0, statement.executeUpdate("BEGIN"));
                assertEquals(1, statement.executeUpdate("INSERT INTO explicit_tx(v) VALUES ('rolled-back')"));
                assertEquals(0, statement.executeUpdate("ROLLBACK"));
                connection.rollback();
            }

            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT v FROM explicit_tx")) {
                assertTrue(rs.next());
                assertEquals("committed", rs.getString(1));
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void explicitTransactionControlDoesNotAutoBeginWhenAutoCommitFalse() throws Exception {
        try (Fixture fixture = open(false)) {
            SshSqliteConnection connection = fixture.connection();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                assertEquals(0, statement.executeUpdate("BEGIN"));
                SQLException error = assertThrows(SQLException.class, () -> statement.executeUpdate("BEGIN"));
                assertTrue(error.getMessage().toLowerCase().contains("transaction"), error.getMessage());
                assertEquals(0, statement.executeUpdate("ROLLBACK"));
                connection.rollback();
            }
        }
    }

    @Test
    void explicitTransactionSqlWorksWhenAutoCommitTrue() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                statement.executeUpdate("CREATE TABLE autocommit_explicit_tx(v TEXT)");
                assertEquals(0, statement.executeUpdate("BEGIN"));
                assertEquals(1, statement.executeUpdate("INSERT INTO autocommit_explicit_tx(v) VALUES ('rolled-back')"));
                assertEquals(0, statement.executeUpdate("ROLLBACK"));
                assertEquals(0, statement.executeUpdate("BEGIN; INSERT INTO autocommit_explicit_tx(v) VALUES ('committed'); COMMIT"));
                try (ResultSet rs = statement.executeQuery("SELECT v FROM autocommit_explicit_tx")) {
                    assertTrue(rs.next());
                    assertEquals("committed", rs.getString(1));
                    assertFalse(rs.next());
                }
            }
        }
    }

    @Test
    void explicitTransactionQueryScriptsReturnRowsAndStillCommit() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                statement.executeUpdate("CREATE TABLE explicit_query_tx(id INTEGER PRIMARY KEY, v TEXT)");

                try (ResultSet rs = statement.executeQuery("BEGIN; INSERT INTO explicit_query_tx(id, v) VALUES (1, 'query'); SELECT v FROM explicit_query_tx WHERE id = 1; COMMIT;")) {
                    assertTrue(rs.next());
                    assertEquals("query", rs.getString(1));
                    assertFalse(rs.next());
                }

                assertTrue(statement.execute("BEGIN; INSERT INTO explicit_query_tx(id, v) VALUES (2, 'execute'); SELECT v FROM explicit_query_tx WHERE id = 2; COMMIT;"));
                try (ResultSet rs = statement.getResultSet()) {
                    assertTrue(rs.next());
                    assertEquals("execute", rs.getString(1));
                    assertFalse(rs.next());
                }

                try (ResultSet rs = statement.executeQuery("SELECT count(*) FROM explicit_query_tx")) {
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt(1));
                }
            }
        }
    }

    @Test
    void preparedExplicitTransactionQueryScriptsReturnRowsAndStillCommit() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                statement.executeUpdate("CREATE TABLE prepared_explicit_query_tx(id INTEGER PRIMARY KEY, v TEXT)");
            }

            try (PreparedStatement query = fixture.connection().prepareStatement("BEGIN; INSERT INTO prepared_explicit_query_tx(id, v) VALUES (?, ?); SELECT v FROM prepared_explicit_query_tx WHERE id = ?; COMMIT;");
                 PreparedStatement execute = fixture.connection().prepareStatement("BEGIN; INSERT INTO prepared_explicit_query_tx(id, v) VALUES (?, ?); SELECT v FROM prepared_explicit_query_tx WHERE id = ?; COMMIT;")) {
                query.setInt(1, 1);
                query.setString(2, "prepared-query");
                query.setInt(3, 1);
                try (ResultSet rs = query.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("prepared-query", rs.getString(1));
                    assertFalse(rs.next());
                }

                execute.setInt(1, 2);
                execute.setString(2, "prepared-execute");
                execute.setInt(3, 2);
                assertTrue(execute.execute());
                try (ResultSet rs = execute.getResultSet()) {
                    assertTrue(rs.next());
                    assertEquals("prepared-execute", rs.getString(1));
                    assertFalse(rs.next());
                }
            }

            try (Statement statement = fixture.connection().createStatement();
                 ResultSet rs = statement.executeQuery("SELECT count(*) FROM prepared_explicit_query_tx")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }
        }
    }

    @Test
    void explicitSavepointSqlSynchronizesJdbcState() throws Exception {
        try (Fixture fixture = open(false)) {
            SshSqliteConnection connection = fixture.connection();
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE explicit_savepoint(v TEXT)");
                assertEquals(0, statement.executeUpdate("SAVEPOINT auto_sp"));
                assertEquals(1, statement.executeUpdate("INSERT INTO explicit_savepoint(v) VALUES ('released')"));
                assertEquals(0, statement.executeUpdate("RELEASE auto_sp"));
            }

            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                assertEquals(0, statement.executeUpdate("SAVEPOINT manual_sp"));
                assertEquals(1, statement.executeUpdate("INSERT INTO explicit_savepoint(v) VALUES ('rolled-back')"));
                assertEquals(0, statement.executeUpdate("ROLLBACK TO manual_sp"));
                assertEquals(0, statement.executeUpdate("RELEASE manual_sp"));
                connection.rollback();
            }

            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT v FROM explicit_savepoint")) {
                assertTrue(rs.next());
                assertEquals("released", rs.getString(1));
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void failedSavepointScriptDoesNotRollbackPriorJdbcTransactionWork() throws Exception {
        try (Fixture fixture = open(false)) {
            SshSqliteConnection connection = fixture.connection();
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE savepoint_failure(v TEXT)");
            }

            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate("INSERT INTO savepoint_failure(v) VALUES ('a')"));
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                        "SAVEPOINT inner_sp; INSERT INTO savepoint_failure(v) VALUES ('b'); INSERT INTO missing_savepoint_failure(v) VALUES ('boom'); RELEASE inner_sp"));

                try (ResultSet rs = statement.executeQuery("SELECT v FROM savepoint_failure ORDER BY v")) {
                    assertTrue(rs.next());
                    assertEquals("a", rs.getString(1));
                    assertFalse(rs.next());
                }
            }
            connection.commit();

            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT v FROM savepoint_failure")) {
                assertTrue(rs.next());
                assertEquals("a", rs.getString(1));
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void failedExplicitScriptAfterCommitDoesNotBreakConnection() throws Exception {
        try (Fixture fixture = open(false)) {
            SshSqliteConnection connection = fixture.connection();
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE commit_then_fail(v TEXT)");
                assertThrows(SQLException.class, () -> statement.executeQuery(
                        "BEGIN; INSERT INTO commit_then_fail(v) VALUES ('committed'); COMMIT; SELECT v FROM missing_commit_then_fail"));

                assertTrue(connection.isValid(1));
                try (ResultSet rs = statement.executeQuery("SELECT v FROM commit_then_fail")) {
                    assertTrue(rs.next());
                    assertEquals("committed", rs.getString(1));
                    assertFalse(rs.next());
                }
                assertEquals(1, statement.executeUpdate("INSERT INTO commit_then_fail(v) VALUES ('usable')"));
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
    void queryResultsPreserveBlobBytesForLiteralAndColumnValues() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                statement.executeUpdate("CREATE TABLE blobs(v BLOB)");
                statement.executeUpdate("INSERT INTO blobs(v) VALUES (X'00ff')");
                try (ResultSet rs = statement.executeQuery("SELECT X'00ff' AS literal_blob, v FROM blobs")) {
                    assertTrue(rs.next());
                    assertArrayEquals(new byte[] {0, (byte) 0xff}, rs.getBytes("literal_blob"));
                    assertArrayEquals(new byte[] {0, (byte) 0xff}, rs.getBytes("v"));
                    assertFalse(rs.next());
                }
            }
        }
    }

    @Test
    void textColumnsWithNumericContentReturnStringsFromGetObject() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                statement.executeUpdate("CREATE TABLE text_numbers(v TEXT)");
                statement.executeUpdate("INSERT INTO text_numbers(v) VALUES ('123')");
                try (ResultSet rs = statement.executeQuery("SELECT v FROM text_numbers")) {
                    assertTrue(rs.next());
                    assertEquals("123", rs.getObject("v"));
                    assertEquals(123, rs.getInt("v"));
                    assertFalse(rs.next());
                }
            }
        }
    }

    @Test
    void typedGetObjectConvertsRealQueryValues() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                statement.executeUpdate("CREATE TABLE typed_objects(i INTEGER, r REAL, t TEXT, b BLOB, n TEXT)");
                statement.executeUpdate("INSERT INTO typed_objects(i, r, t, b, n) VALUES (42, 3.5, '1', X'000f', NULL)");
                try (ResultSet rs = statement.executeQuery("SELECT i, r, t, b, n FROM typed_objects")) {
                    assertTrue(rs.next());
                    assertEquals("42", rs.getObject("i", String.class));
                    assertEquals(Integer.valueOf(42), rs.getObject("i", Integer.class));
                    assertEquals(Short.valueOf((short) 42), rs.getObject("i", Short.class));
                    assertEquals(Byte.valueOf((byte) 42), rs.getObject("i", Byte.class));
                    assertEquals(Long.valueOf(42L), rs.getObject("i", Long.class));
                    assertEquals(Double.valueOf(3.5d), rs.getObject("r", Double.class));
                    assertEquals(Float.valueOf(3.5f), rs.getObject("r", Float.class));
                    assertEquals(Boolean.TRUE, rs.getObject("t", Boolean.class));
                    assertTrue(rs.getBoolean("t"));
                    assertArrayEquals(new byte[] {0, 15}, rs.getObject("b", byte[].class));
                    byte[] firstBlob = (byte[]) rs.getObject("b", Object.class);
                    byte[] secondBlob = (byte[]) rs.getObject("b", Object.class);
                    assertNotSame(firstBlob, secondBlob);
                    firstBlob[0] = 99;
                    assertArrayEquals(new byte[] {0, 15}, secondBlob);
                    byte[] firstUntypedBlob = (byte[]) rs.getObject("b");
                    byte[] secondUntypedBlob = (byte[]) rs.getObject("b");
                    assertNotSame(firstUntypedBlob, secondUntypedBlob);
                    firstUntypedBlob[0] = 99;
                    assertArrayEquals(new byte[] {0, 15}, secondUntypedBlob);
                    assertEquals(new BigDecimal("42"), rs.getObject("i", BigDecimal.class));
                    assertEquals(42L, rs.getObject("i", Object.class));
                    assertEquals(null, rs.getObject("n", String.class));
                    assertTrue(rs.wasNull());
                    assertFalse(rs.next());
                }
            }
        }
    }

    @Test
    void commonTypedGettersConvertRealQueryValues() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement();
                 ResultSet rs = statement.executeQuery("SELECT '2024-05-06' AS d, '12:34:56' AS t, '2024-05-06 12:34:56.789' AS ts, '123.45' AS bd, 3.5 AS f, 127 AS b, 32767 AS s")) {
                assertTrue(rs.next());
                assertEquals(java.sql.Date.valueOf("2024-05-06"), rs.getDate("d"));
                assertEquals(java.sql.Time.valueOf("12:34:56"), rs.getTime("t"));
                assertEquals(java.sql.Timestamp.valueOf("2024-05-06 12:34:56.789"), rs.getTimestamp("ts"));
                assertEquals(new BigDecimal("123.45"), rs.getBigDecimal("bd"));
                assertEquals(3.5f, rs.getFloat("f"), 0.001f);
                assertEquals(127, rs.getByte("b"));
                assertEquals(32767, rs.getShort("s"));
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void numericGettersThrowSqlExceptionOnInvalidConversionsOrOverflow() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement();
                 ResultSet rs = statement.executeQuery("SELECT '2147483648' AS too_int, '9223372036854775808' AS too_long, '1.5' AS fractional, 'not-number' AS bad_number, '1e39' AS too_float, '128' AS too_byte, '32768' AS too_short")) {
                assertTrue(rs.next());
                assertThrows(SQLException.class, () -> rs.getInt("too_int"));
                assertThrows(SQLException.class, () -> rs.getLong("too_long"));
                assertThrows(SQLException.class, () -> rs.getLong("fractional"));
                assertThrows(SQLException.class, () -> rs.getDouble("bad_number"));
                assertThrows(SQLException.class, () -> rs.getFloat("too_float"));
                assertThrows(SQLException.class, () -> rs.getByte("too_byte"));
                assertThrows(SQLException.class, () -> rs.getShort("too_short"));
                assertThrows(SQLException.class, () -> rs.getBigDecimal("bad_number"));
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void typedGetObjectConvertsDateTimeValues() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement();
                 ResultSet rs = statement.executeQuery("SELECT '2024-05-06' AS d, '12:34:56' AS t, '2024-05-06 12:34:56' AS ts")) {
                assertTrue(rs.next());
                assertEquals(java.sql.Date.valueOf("2024-05-06"), rs.getObject("d", java.sql.Date.class));
                assertEquals(java.sql.Time.valueOf("12:34:56"), rs.getObject("t", java.sql.Time.class));
                assertEquals(java.sql.Timestamp.valueOf("2024-05-06 12:34:56"), rs.getObject("ts", java.sql.Timestamp.class));
                assertEquals(LocalDate.parse("2024-05-06"), rs.getObject("d", LocalDate.class));
                assertEquals(LocalTime.parse("12:34:56"), rs.getObject("t", LocalTime.class));
                assertEquals(LocalDateTime.parse("2024-05-06T12:34:56"), rs.getObject("ts", LocalDateTime.class));
            }
        }
    }

    @Test
    void typedGetObjectDateTimeParseFailuresThrowSqlException() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement();
                 ResultSet rs = statement.executeQuery("SELECT 'not-a-date' AS d, 'bad-time' AS t, 'not-a-timestamp' AS ts")) {
                assertTrue(rs.next());
                assertThrows(SQLException.class, () -> rs.getObject("d", java.sql.Date.class));
                assertThrows(SQLException.class, () -> rs.getObject("t", java.sql.Time.class));
                assertThrows(SQLException.class, () -> rs.getObject("ts", java.sql.Timestamp.class));
                assertThrows(SQLException.class, () -> rs.getObject("d", LocalDate.class));
                assertThrows(SQLException.class, () -> rs.getObject("t", LocalTime.class));
                assertThrows(SQLException.class, () -> rs.getObject("ts", LocalDateTime.class));
            }
        }
    }

    @Test
    void typedGetObjectThrowsOnNarrowNumericOverflowOrRounding() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement();
                 ResultSet rs = statement.executeQuery("SELECT 128 AS too_byte, 32768 AS too_short, 2147483648 AS too_int, 9223372036854775808.0 AS too_long, 1.5 AS fractional")) {
                assertTrue(rs.next());
                assertThrows(SQLException.class, () -> rs.getObject("too_byte", Byte.class));
                assertThrows(SQLException.class, () -> rs.getObject("too_short", Short.class));
                assertThrows(SQLException.class, () -> rs.getObject("too_int", Integer.class));
                assertThrows(SQLException.class, () -> rs.getObject("too_long", Long.class));
                assertThrows(SQLException.class, () -> rs.getObject("fractional", Integer.class));
                assertFalse(rs.next());
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
    void executesMultipleStatementScriptsThroughSqliteCli() throws Exception {
        try (Fixture fixture = open(false)) {
            SshSqliteConnection connection = fixture.connection();
            try (Statement statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate("CREATE TABLE multi(v TEXT); INSERT INTO multi(v) VALUES ('a'); INSERT INTO multi(v) VALUES ('b')"));
            }
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT count(*) AS c FROM multi")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getLong("c"));
            }
        }
    }

    @Test
    void failedExplicitTransactionScriptRollsBackNonPooledConnection() throws Exception {
        Path db = tempDir.resolve("failed-explicit-nonpool.db");
        Fixture fixture = open(false, db, 10_000, 1_000);
        try {
            try (Statement statement = fixture.connection().createStatement()) {
                statement.executeUpdate("CREATE TABLE failed_explicit(v TEXT)");
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                        "BEGIN IMMEDIATE; INSERT INTO failed_explicit(v) VALUES ('uncommitted'); INSERT INTO missing_failed_explicit(v) VALUES ('boom')"));
                try (ResultSet rs = statement.executeQuery("SELECT count(*) AS c FROM failed_explicit")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getLong("c"));
                }
            }
        } finally {
            fixture.close();
        }

        Process check = new ProcessBuilder("/usr/bin/sqlite3", db.toString(), "SELECT count(*) FROM failed_explicit").start();
        String output = new String(check.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, check.waitFor());
        assertEquals("0", output);
    }

    @Test
    void failedExplicitTransactionScriptIsEvictedFromPool() throws Exception {
        Path db = tempDir.resolve("failed-explicit-pool.db");
        try (SshSqliteDataSource dataSource = pooledDataSource(db)) {
            try (java.sql.Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE pooled_failed_explicit(v TEXT)");
            }

            try (java.sql.Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                        "BEGIN IMMEDIATE; INSERT INTO pooled_failed_explicit(v) VALUES ('uncommitted'); INSERT INTO missing_pooled_failed_explicit(v) VALUES ('boom')"));
            }

            try (java.sql.Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                assertEquals(0, statement.executeUpdate("BEGIN"));
                assertEquals(0, statement.executeUpdate("ROLLBACK"));
                try (ResultSet rs = statement.executeQuery("SELECT count(*) AS c FROM pooled_failed_explicit")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getLong("c"));
                }
            }
        }
    }

    @Test
    void rowProducingExplicitTransactionStatementRollsBackOnPoolReturn() throws Exception {
        Path db = tempDir.resolve("query-explicit-pool.db");
        try (SshSqliteDataSource dataSource = pooledDataSource(db)) {
            try (java.sql.Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                try (ResultSet rs = statement.executeQuery("BEGIN IMMEDIATE; SELECT 1 AS v")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt("v"));
                }
            }

            try (java.sql.Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                assertEquals(0, statement.executeUpdate("BEGIN"));
                assertEquals(0, statement.executeUpdate("ROLLBACK"));
            }
        }
    }

    @Test
    void rowProducingExplicitTransactionPreparedStatementRollsBackOnPoolReturn() throws Exception {
        Path db = tempDir.resolve("query-explicit-prepared-pool.db");
        try (SshSqliteDataSource dataSource = pooledDataSource(db)) {
            try (java.sql.Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement("SAVEPOINT ps_sp; SELECT ? AS v")) {
                ps.setInt(1, 7);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(7, rs.getInt("v"));
                }
            }

            try (java.sql.Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                assertEquals(0, statement.executeUpdate("BEGIN"));
                assertEquals(0, statement.executeUpdate("ROLLBACK"));
            }
        }
    }

    @Test
    void failedStatementExecutionClearsPreviousResultAndUpdateCount() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                assertEquals(0, statement.executeUpdate("CREATE TABLE stale_statement(v TEXT)"));
                assertEquals(1, statement.executeUpdate("INSERT INTO stale_statement(v) VALUES ('one')"));
                assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO missing_stale_statement(v) VALUES ('boom')"));
                assertEquals(-1, statement.getLargeUpdateCount());
                assertEquals(null, statement.getResultSet());

                try (ResultSet rs = statement.executeQuery("SELECT v FROM stale_statement")) {
                    assertTrue(rs.next());
                    assertEquals("one", rs.getString(1));
                }
                assertThrows(SQLException.class, () -> statement.executeQuery("SELECT v FROM missing_stale_statement"));
                assertEquals(-1, statement.getLargeUpdateCount());
                assertEquals(null, statement.getResultSet());
            }
        }
    }

    @Test
    void failedPreparedStatementExecutionClearsPreviousResultAndUpdateCount() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                statement.executeUpdate("CREATE TABLE stale_prepared_update(v TEXT)");
                statement.executeUpdate("CREATE TABLE stale_prepared_query(v TEXT)");
                statement.executeUpdate("INSERT INTO stale_prepared_query(v) VALUES ('one')");
            }
            try (PreparedStatement insert = fixture.connection().prepareStatement("INSERT INTO stale_prepared_update(v) VALUES (?)")) {
                insert.setString(1, "one");
                assertEquals(1, insert.executeUpdate());
                try (Statement statement = fixture.connection().createStatement()) {
                    statement.executeUpdate("DROP TABLE stale_prepared_update");
                }
                insert.setString(1, "two");
                assertThrows(SQLException.class, () -> insert.executeUpdate());
                assertEquals(-1, insert.getLargeUpdateCount());
                assertEquals(null, insert.getResultSet());
            }
            try (PreparedStatement query = fixture.connection().prepareStatement("SELECT v FROM stale_prepared_query")) {
                try (ResultSet rs = query.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("one", rs.getString(1));
                }
                try (Statement statement = fixture.connection().createStatement()) {
                    statement.executeUpdate("DROP TABLE stale_prepared_query");
                }
                assertThrows(SQLException.class, () -> query.executeQuery());
                assertEquals(-1, query.getLargeUpdateCount());
                assertEquals(null, query.getResultSet());
            }
        }
    }

    @Test
    void nonDmlUpdateCountsDoNotLeakPreviousChanges() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                assertEquals(0, statement.executeUpdate("CREATE TABLE update_counts(v TEXT)"));
                assertEquals(1, statement.executeUpdate("INSERT INTO update_counts(v) VALUES ('one')"));
                assertEquals(0, statement.executeUpdate("CREATE TABLE update_counts_ddl(v TEXT)"));
                assertEquals(0, statement.executeUpdate("PRAGMA cache_size=-2000"));
                assertEquals(0, statement.executeUpdate("INSERT INTO update_counts(v) VALUES ('two'); CREATE TABLE update_counts_tail(v TEXT)"));
                assertEquals(1, statement.executeUpdate("CREATE TABLE update_counts_tail_dml(v TEXT); INSERT INTO update_counts_tail_dml(v) VALUES ('last')"));
            }
        }
    }

    @Test
    void createTriggerScriptKeepsBodySemicolonsTogetherAndFires() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                assertEquals(1, statement.executeUpdate(
                        "CREATE TABLE trigger_items(id INTEGER PRIMARY KEY, name TEXT); " +
                                "CREATE TABLE trigger_audit(item_id INTEGER, event TEXT); " +
                                "CREATE TRIGGER trigger_items_ai AFTER INSERT ON trigger_items BEGIN " +
                                "INSERT INTO trigger_audit(item_id, event) VALUES (new.id, 'before'); " +
                                "UPDATE trigger_audit SET event = event || ':after' WHERE item_id = new.id; " +
                                "END; " +
                                "INSERT INTO trigger_items(name) VALUES ('alpha')"));
                try (ResultSet rs = statement.executeQuery("SELECT item_id, event FROM trigger_audit")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getLong("item_id"));
                    assertEquals("before:after", rs.getString("event"));
                    assertFalse(rs.next());
                }
            }
        }
    }

    @Test
    void createTriggerScriptDoesNotTreatCaseEndAsTriggerEnd() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                assertEquals(1, statement.executeUpdate(
                        "CREATE TABLE trigger_case_items(id INTEGER PRIMARY KEY, name TEXT); " +
                                "CREATE TABLE trigger_case_audit(item_id INTEGER, event TEXT); " +
                                "CREATE TRIGGER trigger_case_items_ai AFTER INSERT ON trigger_case_items BEGIN " +
                                "INSERT INTO trigger_case_audit(item_id, event) VALUES (new.id, CASE WHEN new.name IS NULL THEN 'missing' ELSE 'named' END); " +
                                "UPDATE trigger_case_audit SET event = event || ':after' WHERE item_id = new.id; " +
                                "END; " +
                                "INSERT INTO trigger_case_items(name) VALUES ('alpha')"));
                try (ResultSet rs = statement.executeQuery("SELECT item_id, event FROM trigger_case_audit")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getLong("item_id"));
                    assertEquals("named:after", rs.getString("event"));
                    assertFalse(rs.next());
                }
            }
        }
    }

    @Test
    void withInsertReturningIsResultSetAndUpdateMethodsRejectBeforeMutation() throws Exception {
        assumeTrue(sqliteSupportsReturning(), "local sqlite3 does not support RETURNING");
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                statement.executeUpdate("CREATE TABLE returning_items(id INTEGER PRIMARY KEY, name TEXT)");

                assertTrue(statement.execute("WITH source(name) AS (SELECT 'alpha') INSERT INTO returning_items(name) SELECT name FROM source RETURNING id, name"));
                assertEquals(-1, statement.getUpdateCount());
                try (ResultSet rs = statement.getResultSet()) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getLong("id"));
                    assertEquals("alpha", rs.getString("name"));
                    assertFalse(rs.next());
                }

                try (ResultSet rs = statement.executeQuery("WITH source(name) AS (SELECT 'beta') INSERT INTO returning_items(name) SELECT name FROM source RETURNING id, name")) {
                    assertTrue(rs.next());
                    assertEquals(2, rs.getLong("id"));
                    assertEquals("beta", rs.getString("name"));
                    assertFalse(rs.next());
                }

                SQLException error = assertThrows(SQLException.class, () -> statement.executeUpdate(
                        "WITH source(name) AS (SELECT 'must-not-insert') INSERT INTO returning_items(name) SELECT name FROM source RETURNING id"));
                assertTrue(error.getMessage().contains("does not return rows"), error.getMessage());
                try (ResultSet rs = statement.executeQuery("SELECT count(*) AS c FROM returning_items WHERE name = 'must-not-insert'")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getLong("c"));
                }

                SQLException largeError = assertThrows(SQLException.class, () -> statement.executeLargeUpdate(
                        "WITH source(name) AS (SELECT 'must-not-large-insert') INSERT INTO returning_items(name) SELECT name FROM source RETURNING id"));
                assertTrue(largeError.getMessage().contains("does not return rows"), largeError.getMessage());
                try (ResultSet rs = statement.executeQuery("SELECT count(*) AS c FROM returning_items WHERE name = 'must-not-large-insert'")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getLong("c"));
                }
            }
        }
    }

    @Test
    void maxRowsTruncatesWithInsertReturningWithoutWrappingAsSelectSubquery() throws Exception {
        assumeTrue(sqliteSupportsReturning(), "local sqlite3 does not support RETURNING");
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                statement.executeUpdate("CREATE TABLE limited_returning_items(id INTEGER PRIMARY KEY, name TEXT)");
                statement.setMaxRows(1);
                try (ResultSet rs = statement.executeQuery(
                        "WITH source(name) AS (VALUES ('alpha'), ('beta')) " +
                                "INSERT INTO limited_returning_items(name) SELECT name FROM source RETURNING id, name")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getLong("id"));
                    assertEquals("alpha", rs.getString("name"));
                    assertFalse(rs.next());
                }
                statement.setMaxRows(0);
                try (ResultSet rs = statement.executeQuery("SELECT count(*) AS c FROM limited_returning_items")) {
                    assertTrue(rs.next());
                    assertEquals(2, rs.getLong("c"));
                }
            }
        }
    }

    @Test
    void multiStatementMutatingScriptStopsAfterFirstError() throws Exception {
        Path db = tempDir.resolve("script-error.db");
        try (Fixture fixture = open(false, db, 10_000, 1_000)) {
            try (Statement statement = fixture.connection().createStatement()) {
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                        "CREATE TABLE script_errors(v TEXT); " +
                                "INSERT INTO missing_table(v) VALUES ('fails'); " +
                                "INSERT INTO script_errors(v) VALUES ('must-not-run')"));
                try (ResultSet rs = statement.executeQuery("SELECT 1 AS still_usable")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt("still_usable"));
                }
            }
        }
        Process check = new ProcessBuilder("/usr/bin/sqlite3", db.toString(), "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='script_errors'").start();
        assertEquals(0, check.waitFor());
        String count = new String(check.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals("0", count);
    }

    @Test
    void explicitTransactionScriptKeepsSqliteSemanticsAndConnectionUsableAfterError() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                statement.executeUpdate("CREATE TABLE explicit_script(v TEXT)");
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                        "BEGIN; INSERT INTO explicit_script(v) VALUES ('kept'); INSERT INTO missing_table(v) VALUES ('fails'); COMMIT"));
                try (ResultSet rs = statement.executeQuery("SELECT count(*) AS c FROM explicit_script")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getLong("c"));
                }
            }
        }
    }

    @Test
    void ordinarySqlErrorDoesNotPoisonCliConnection() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                SQLException error = assertThrows(SQLException.class, () -> statement.executeQuery("SELECT * FROM missing_table"));
                assertTrue(error.getMessage().contains("missing_table"), error.getMessage());

                try (ResultSet rs = statement.executeQuery("SELECT 1 AS ok")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt("ok"));
                    assertFalse(rs.next());
                }
            }
        }
    }

    @Test
    void emptyResultSetPreservesCsvHeaderMetadata() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                statement.executeUpdate("CREATE TABLE empty_meta(id INTEGER, name TEXT)");
                try (ResultSet rs = statement.executeQuery("SELECT id, name FROM empty_meta WHERE 0")) {
                    assertEquals(2, rs.getMetaData().getColumnCount());
                    assertEquals("id", rs.getMetaData().getColumnName(1));
                    assertEquals("name", rs.getMetaData().getColumnName(2));
                    assertFalse(rs.next());
                }
            }
        }
    }

    @Test
    void emptyResultSetPreservesExpressionAndCompoundHeaders() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                try (ResultSet rs = statement.executeQuery("SELECT 1 AS literal WHERE 0")) {
                    assertEquals(1, rs.getMetaData().getColumnCount());
                    assertEquals("literal", rs.getMetaData().getColumnName(1));
                    assertFalse(rs.next());
                }
                try (ResultSet rs = statement.executeQuery("WITH c(x) AS (SELECT 1) SELECT x AS with_value FROM c WHERE 0")) {
                    assertEquals(1, rs.getMetaData().getColumnCount());
                    assertEquals("with_value", rs.getMetaData().getColumnName(1));
                    assertFalse(rs.next());
                }
                try (ResultSet rs = statement.executeQuery("SELECT 1 AS compound_value UNION ALL SELECT 2 LIMIT 0")) {
                    assertEquals(1, rs.getMetaData().getColumnCount());
                    assertEquals("compound_value", rs.getMetaData().getColumnName(1));
                    assertFalse(rs.next());
                }
            }
        }
    }

    @Test
    void blobExpressionMetadataUsesFirstRowValueType() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement(); ResultSet rs = statement.executeQuery("SELECT X'00ff' AS payload")) {
                ResultSetMetaData metaData = rs.getMetaData();
                assertEquals(Types.BLOB, metaData.getColumnType(1));
                assertEquals("BLOB", metaData.getColumnTypeName(1));
                assertEquals(byte[].class.getName(), metaData.getColumnClassName(1));
                assertTrue(rs.next());
                assertArrayEquals(new byte[] {0, (byte) 0xff}, rs.getBytes(1));
            }
        }
    }

    @Test
    void delayedSqliteStderrAfterMarkerStillFailsStatement() throws Exception {
        Path script = tempDir.resolve("delayed-stderr-sqlite3.sh");
        java.nio.file.Files.writeString(script, "#!/bin/sh\n" +
                "seen=0\n" +
                "while IFS= read -r line; do\n" +
                "  case \"$line\" in\n" +
                "    \".print \"*)\n" +
                "      marker=${line#.print }\n" +
                "      seen=$((seen + 1))\n" +
                "      if [ \"$seen\" -eq 1 ]; then\n" +
                "        printf 'version\\n3.40.1\\n%s\\n' \"$marker\"\n" +
                "      else\n" +
                "        printf '%s\\n' \"$marker\"\n" +
                "        sleep 0.05\n" +
                "        printf 'Parse error: no such table: delayed_missing\\n' >&2\n" +
                "      fi\n" +
                "      ;;\n" +
                "  esac\n" +
                "done\n");
        assertTrue(script.toFile().setExecutable(true));

        Properties props = new Properties();
        props.setProperty("db.path", tempDir.resolve("delayed-stderr.db").toString());
        ConnectionConfig config = ConnectionConfig.parse("jdbc:sshsqlite://u@example.com/ignored.db", props);
        Process process = new ProcessBuilder(script.toString()).start();
        var executor = Executors.newSingleThreadExecutor();
        BoundedCapture stderr = new BoundedCapture(process.getErrorStream(), config.stderrMaxBufferedBytes);
        executor.submit(stderr);
        CliProtocolClient client = new CliProtocolClient(process.getInputStream(), process.getOutputStream(), config, stderr, () -> {
            process.destroyForcibly();
            executor.shutdownNow();
        });

        java.io.IOException error = assertThrows(java.io.IOException.class, () -> client.query("SELECT * FROM delayed_missing", 0, 1, 5_000));
        assertTrue(error.getMessage().contains("delayed_missing"), error.getMessage());
        client.closeReader();
        process.destroyForcibly();
        executor.shutdownNow();
    }

    @Test
    void startupFailureIncludesSqliteStderrDiagnostics() throws Exception {
        Path script = tempDir.resolve("startup-fails-sqlite3.sh");
        java.nio.file.Files.writeString(script, "#!/bin/sh\n" +
                "printf 'startup exploded: missing sqlite extension\\n' >&2\n" +
                "exit 1\n");
        assertTrue(script.toFile().setExecutable(true));

        Properties props = new Properties();
        props.setProperty("db.path", tempDir.resolve("startup-fails.db").toString());
        ConnectionConfig config = ConnectionConfig.parse("jdbc:sshsqlite://u@example.com/ignored.db", props);
        Process process = new ProcessBuilder(script.toString()).start();
        var executor = Executors.newSingleThreadExecutor();
        BoundedCapture stderr = new BoundedCapture(process.getErrorStream(), config.stderrMaxBufferedBytes);
        executor.submit(stderr);

        java.io.IOException error = assertThrows(java.io.IOException.class,
                () -> new CliProtocolClient(process.getInputStream(), process.getOutputStream(), config, stderr, () -> {
                    process.destroyForcibly();
                    executor.shutdownNow();
                }));
        assertTrue(error.getMessage().contains("sqlite3 CLI startup failed"), error.getMessage());
        assertTrue(error.getMessage().contains("startup exploded"), error.getMessage());
        process.destroyForcibly();
        executor.shutdownNow();
    }

    @Test
    void textEqualToLegacyNullSentinelStaysText() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                statement.executeUpdate("CREATE TABLE sentinel(v TEXT)");
                statement.executeUpdate("INSERT INTO sentinel(v) VALUES ('__SSHSQLITE_NULL__')");
                try (ResultSet rs = statement.executeQuery("SELECT v FROM sentinel")) {
                    assertTrue(rs.next());
                    assertEquals("__SSHSQLITE_NULL__", rs.getString(1));
                    assertFalse(rs.wasNull());
                }
            }
        }
    }

    @Test
    void duplicateColumnLabelsPreservePositions() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement(); ResultSet rs = statement.executeQuery("SELECT 1 AS x, 2 AS x")) {
                assertEquals(2, rs.getMetaData().getColumnCount());
                assertEquals("x", rs.getMetaData().getColumnName(1));
                assertEquals("x", rs.getMetaData().getColumnName(2));
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals(2, rs.getInt(2));
                assertEquals(1, rs.getInt("x"));
            }
        }
    }

    @Test
    void executeTreatsPragmaSetterAsNoResultUpdate() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                assertFalse(statement.execute("PRAGMA foreign_keys=ON"));
                try (ResultSet rs = statement.executeQuery("PRAGMA foreign_keys")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                }
            }
        }
    }

    @Test
    void executeTreatsCommonPragmaAssignmentsAsNoResultUpdates() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                assertFalse(statement.execute("PRAGMA synchronous=NORMAL"));
                assertEquals(0, statement.executeUpdate("PRAGMA cache_size=-2000"));
                assertEquals(0, statement.executeUpdate("PRAGMA temp_store=MEMORY"));

                try (ResultSet rs = statement.executeQuery("PRAGMA cache_size")) {
                    assertTrue(rs.next());
                    assertEquals(-2000, rs.getInt(1));
                }
            }
        }
    }

    @Test
    void rejectsMultipleRowProducingStatementsBeforeExecution() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                SQLException error = assertThrows(SQLException.class, () -> statement.execute("SELECT 1 AS a; SELECT 2 AS b"));
                assertTrue(error.getMessage().contains("Multiple row-producing"), error.getMessage());

                assertEquals(0, statement.executeUpdate("CREATE TABLE multi_row_reject(v TEXT)"));
                SQLException updateError = assertThrows(SQLException.class, () -> statement.executeUpdate(
                        "INSERT INTO multi_row_reject(v) VALUES ('must-not-run'); SELECT v FROM multi_row_reject"));
                assertTrue(updateError.getMessage().contains("does not return rows"), updateError.getMessage());
                try (ResultSet rs = statement.executeQuery("SELECT count(*) AS c FROM multi_row_reject")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt("c"));
                }
            }
        }
    }

    @Test
    void quotedReturningIdentifiersDoNotMakeUpdatesLookRowProducing() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                statement.executeUpdate("CREATE TABLE quoted_returning(id INTEGER PRIMARY KEY, \"returning\" TEXT)");
                assertEquals(1, statement.executeUpdate("INSERT INTO quoted_returning(\"returning\") VALUES ('before')"));
                assertFalse(statement.execute("UPDATE quoted_returning SET \"returning\" = 'after' WHERE id = 1"));
                try (ResultSet rs = statement.executeQuery("SELECT \"returning\" FROM quoted_returning")) {
                    assertTrue(rs.next());
                    assertEquals("after", rs.getString(1));
                    assertFalse(rs.next());
                }
            }
        }
    }

    @Test
    void quotedCteNamedReturningDoesNotMakeUpdateLookRowProducing() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                statement.executeUpdate("CREATE TABLE cte_returning_name(id INTEGER PRIMARY KEY, v TEXT)");
                statement.executeUpdate("INSERT INTO cte_returning_name(v) VALUES ('before')");
                assertEquals(1, statement.executeUpdate(
                        "WITH \"returning\"(v) AS (SELECT 'after') UPDATE cte_returning_name SET v = (SELECT v FROM \"returning\") WHERE id = 1"));
                try (ResultSet rs = statement.executeQuery("SELECT v FROM cte_returning_name")) {
                    assertTrue(rs.next());
                    assertEquals("after", rs.getString(1));
                }
            }
        }
    }

    @Test
    void resultSetIsFirstOnlyForFirstRow() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement(); ResultSet rs = statement.executeQuery("SELECT 1 AS v UNION ALL SELECT 2")) {
                assertFalse(rs.isFirst());
                assertTrue(rs.next());
                assertTrue(rs.isFirst());
                assertEquals(1, rs.getInt("v"));
                assertTrue(rs.next());
                assertFalse(rs.isFirst());
                assertEquals(2, rs.getInt("v"));
                assertFalse(rs.next());
                assertFalse(rs.isFirst());
            }
        }
    }

    @Test
    void maxRowsLimitsSimpleSelectBeforeCliBuffering() throws Exception {
        Path db = tempDir.resolve("maxrows.db");
        try (Fixture fixture = open(false, db, 5_000, 1_000, 180)) {
            try (Statement statement = fixture.connection().createStatement()) {
                statement.executeUpdate("CREATE TABLE many(v TEXT)");
                statement.executeUpdate("WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 20) INSERT INTO many(v) SELECT printf('%0100d', i) FROM n");
                statement.setMaxRows(1);
                try (ResultSet rs = statement.executeQuery("SELECT v FROM many ORDER BY v")) {
                    assertTrue(rs.next());
                    assertFalse(rs.next());
                }
            }
        }
    }

    @Test
    void maxRowsSkipsLeadingCommentsBeforeClassifyingSelect() throws Exception {
        Path db = tempDir.resolve("comment-maxrows.db");
        try (Fixture fixture = open(false, db, 5_000, 1_000, 180)) {
            try (Statement statement = fixture.connection().createStatement()) {
                statement.executeUpdate("CREATE TABLE commented_many(v TEXT)");
                statement.executeUpdate("WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 20) INSERT INTO commented_many(v) SELECT printf('%0100d', i) FROM n");
                statement.setMaxRows(1);
                try (ResultSet rs = statement.executeQuery("-- leading comment\n/* and block comment */\nSELECT v FROM commented_many ORDER BY v")) {
                    assertTrue(rs.next());
                    assertFalse(rs.next());
                }
            }
        }
    }

    @Test
    void preparedStatementIgnoresQuestionMarksInBracketQuotedIdentifiers() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                statement.executeUpdate("CREATE TABLE [br?acket]([we?ird] TEXT)");
                statement.executeUpdate("INSERT INTO [br?acket]([we?ird]) VALUES ('ok')");
            }
            try (PreparedStatement ps = fixture.connection().prepareStatement("SELECT [we?ird] FROM [br?acket] WHERE [we?ird] = ?")) {
                ps.setString(1, "ok");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("ok", rs.getString(1));
                    assertFalse(rs.next());
                }
            }
        }
    }

    @Test
    void metadataReadOnlyFollowsConnectionMode() throws Exception {
        try (Fixture fixture = open(false)) {
            assertFalse(fixture.connection().isReadOnly());
            assertFalse(fixture.connection().getMetaData().isReadOnly());
        }
        try (Fixture fixture = open(true)) {
            assertTrue(fixture.connection().isReadOnly());
            assertTrue(fixture.connection().getMetaData().isReadOnly());
        }
    }

    @Test
    void rejectsDotCommandsInsideScripts() throws Exception {
        try (Fixture fixture = open(false)) {
            try (Statement statement = fixture.connection().createStatement()) {
                SQLException error = assertThrows(SQLException.class, () -> statement.executeUpdate("CREATE TABLE dot_commands(v TEXT);\n.tables"));
                assertTrue(error.getMessage().contains("dot commands"));
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
    void parseFailureAbortsSqliteProcess() throws Exception {
        Path script = tempDir.resolve("malformed-after-startup-sqlite3.sh");
        java.nio.file.Files.writeString(script, "#!/bin/sh\n" +
                "seen=0\n" +
                "while IFS= read -r line; do\n" +
                "  case \"$line\" in\n" +
                "    \".print \"*)\n" +
                "      marker=${line#.print }\n" +
                "      seen=$((seen + 1))\n" +
                "      if [ \"$seen\" -eq 1 ]; then\n" +
                "        printf 'version\\n3.40.1\\n%s\\n' \"$marker\"\n" +
                "      else\n" +
                "        printf '%s\\n' 'value' \"'unterminated\" \"$marker\"\n" +
                "      fi\n" +
                "      ;;\n" +
                "  esac\n" +
                "done\n");
        assertTrue(script.toFile().setExecutable(true));

        Properties props = new Properties();
        props.setProperty("db.path", tempDir.resolve("malformed.db").toString());
        ConnectionConfig config = ConnectionConfig.parse("jdbc:sshsqlite://u@example.com/ignored.db", props);
        Process process = new ProcessBuilder(script.toString()).start();
        var executor = Executors.newSingleThreadExecutor();
        BoundedCapture stderr = new BoundedCapture(process.getErrorStream(), config.stderrMaxBufferedBytes);
        executor.submit(stderr);
        CliProtocolClient client = new CliProtocolClient(process.getInputStream(), process.getOutputStream(), config, stderr, () -> {
            process.destroyForcibly();
            executor.shutdownNow();
        });

        java.io.IOException error = assertThrows(java.io.IOException.class, () -> client.query("SELECT 1 AS value", 0, 1, 5_000));
        assertTrue(error.getMessage().contains("Unable to parse sqlite3 output"));
        assertTrue(client.isBroken());
        assertTrue(process.waitFor(2, TimeUnit.SECONDS), "sqlite3 process should be killed on parse failure");
    }

    @Test
    void writeFailureMarksCliConnectionBrokenAndAbortsBackend() throws Exception {
        Properties props = new Properties();
        props.setProperty("db.path", tempDir.resolve("write-failure.db").toString());
        ConnectionConfig config = ConnectionConfig.parse("jdbc:sshsqlite://u@example.com/ignored.db", props);
        PipedInputStream input = new PipedInputStream();
        PipedOutputStream stdout = new PipedOutputStream(input);
        FailingAfterStartupOutputStream output = new FailingAfterStartupOutputStream(stdout);
        AtomicBoolean aborted = new AtomicBoolean(false);

        CliProtocolClient client = new CliProtocolClient(input, output, config, null, () -> aborted.set(true));

        IOException error = assertThrows(IOException.class, () -> client.query("SELECT 1 AS value", 0, 1, 5_000));
        assertTrue(error.getMessage().contains("simulated write failure"), error.getMessage());
        assertTrue(client.isBroken());
        assertTrue(aborted.get());
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
        try (SshSqliteDataSource dataSource = pooledDataSource(db)) {
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

    private SshSqliteDataSource pooledDataSource(Path db) {
        SshSqliteDataSource dataSource = new SshSqliteDataSource();
        dataSource.setUrl("jdbc:sshsqlite://local" + db);
        dataSource.setProperty("helper.transport", "local");
        dataSource.setProperty("sqlite3.path", "/usr/bin/sqlite3");
        dataSource.setProperty("pool.enabled", "true");
        dataSource.setProperty("pool.maxSize", "1");
        return dataSource;
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
        Process process = new ProcessBuilder(command).start();
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

    private static boolean sqliteSupportsReturning() throws Exception {
        Process process = new ProcessBuilder("/usr/bin/sqlite3", ":memory:", "CREATE TABLE t(v); INSERT INTO t(v) VALUES (1) RETURNING v;").start();
        return process.waitFor() == 0;
    }

    private static boolean sqliteVersionAtLeast(String version, String minimum) {
        String[] actualParts = version.split("\\.");
        String[] minimumParts = minimum.split("\\.");
        for (int i = 0; i < 3; i++) {
            int actual = i < actualParts.length ? parseVersionPart(actualParts[i]) : 0;
            int required = i < minimumParts.length ? parseVersionPart(minimumParts[i]) : 0;
            if (actual != required) return actual > required;
        }
        return true;
    }

    private static int parseVersionPart(String value) {
        try {
            return Integer.parseInt(value.replaceAll("[^0-9].*", ""));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static final class FailingAfterStartupOutputStream extends OutputStream {
        private final PipedOutputStream stdout;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private boolean startupComplete;

        FailingAfterStartupOutputStream(PipedOutputStream stdout) {
            this.stdout = stdout;
        }

        @Override
        public void write(int b) throws IOException {
            if (startupComplete) {
                throw new IOException("simulated write failure");
            }
            if (b == '\n') {
                handleLine();
            } else if (b != '\r') {
                line.write(b);
            }
        }

        @Override
        public void close() throws IOException {
            stdout.close();
        }

        private void handleLine() throws IOException {
            String text = line.toString(StandardCharsets.UTF_8);
            line.reset();
            if (text.startsWith(".print ")) {
                String marker = text.substring(".print ".length());
                stdout.write(("version\n3.40.1\n" + marker + "\n").getBytes(StandardCharsets.UTF_8));
                stdout.flush();
                startupComplete = true;
            }
        }
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
