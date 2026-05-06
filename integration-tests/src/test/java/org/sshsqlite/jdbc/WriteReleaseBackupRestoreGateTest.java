package org.sshsqlite.jdbc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("writeRelease")
class WriteReleaseBackupRestoreGateTest {
    @TempDir Path tempDir;

    @Test
    void rehearsesBackupRestoreAgainstDisposableWalDatabaseAndBrowsesRestoredCopyReadonly() throws Exception {
        Path liveDisposableDb = tempDir.resolve("live-disposable.db");
        Path backup = tempDir.resolve("backup.sqlite");
        Path restored = tempDir.resolve("restored.sqlite");

        runSqlite(liveDisposableDb,
                "PRAGMA journal_mode=WAL;" +
                        "CREATE TABLE release_gate(id INTEGER PRIMARY KEY, name TEXT NOT NULL);" +
                        "INSERT INTO release_gate(name) VALUES ('alpha'), ('beta');" +
                        "VACUUM INTO '" + sqliteQuote(backup.toString()) + "';");
        Files.copy(backup, restored);

        String integrity = runSqlite(restored, "PRAGMA integrity_check;").trim();
        assertEquals("ok", integrity);

        try (Connection connection = DriverManager.getConnection("jdbc:sshsqlite://local" + restored, readonlyProperties());
             Statement statement = connection.createStatement()) {
            DatabaseMetaData meta = connection.getMetaData();
            boolean tableFound = false;
            try (ResultSet tables = meta.getTables(null, "main", "release_gate", new String[]{"TABLE"})) {
                while (tables.next()) {
                    tableFound |= "release_gate".equals(tables.getString("TABLE_NAME"));
                }
            }
            assertTrue(tableFound, "restored disposable backup should be browsable through SSHSQLite metadata");

            try (ResultSet rows = statement.executeQuery("SELECT count(*) AS c FROM release_gate")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt("c"));
            }
        }

        writeEvidence(restored, integrity);
    }

    private Properties readonlyProperties() {
        Properties props = new Properties();
        props.setProperty("ssh.user", "local");
        props.setProperty("helper.transport", "local");
        props.setProperty("sqlite3.path", "sqlite3");
        props.setProperty("sqlite3.startupTimeoutMs", "10000");
        props.setProperty("stderr.maxBufferedBytes", "4096");
        props.setProperty("readonly", "true");
        return props;
    }

    private void writeEvidence(Path restored, String integrity) throws Exception {
        String reportPath = System.getProperty("sshsqlite.writeReleaseReport");
        assertTrue(reportPath != null && !reportPath.isBlank(), "sshsqlite.writeReleaseReport must be configured for write release evidence");
        Path report = Path.of(reportPath);
        Files.createDirectories(report.getParent());
        Files.writeString(report,
                "{\n" +
                "  \"schema\": \"sshsqlite-write-release-backup-restore-v1\",\n" +
                "  \"status\": \"pass\",\n" +
                "  \"createdAt\": \"" + Instant.now() + "\",\n" +
                "  \"databaseScope\": \"disposable\",\n" +
                "  \"journalMode\": \"WAL\",\n" +
                "  \"backupMethod\": \"VACUUM INTO\",\n" +
                "  \"restoreTarget\": \"disposable\",\n" +
                "  \"integrityCheck\": \"" + jsonEscape(integrity) + "\",\n" +
                "  \"sshsqliteReadonlyBrowse\": \"pass\",\n" +
                "  \"restoredFixtureSha256\": \"" + sha256(restored) + "\"\n" +
                "}\n");
        assertTrue(Files.isRegularFile(report), "write release evidence was not written to " + report);
    }

    private String runSqlite(Path db, String sql) throws Exception {
        Process process = new ProcessBuilder("sqlite3", db.toString(), sql).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
        return output;
    }

    private static String sqliteQuote(String value) {
        return value.replace("'", "''");
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return hex.toString();
    }
}
