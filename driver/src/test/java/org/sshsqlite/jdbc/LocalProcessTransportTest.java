package org.sshsqlite.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalProcessTransportTest {
    @Test
    void validatesHandshakeOpenAndRepeatedPingOnSameProcess() throws Exception {
        try (LocalProcessTransport transport = LocalProcessTransport.start(javaCommand("normal"), config())) {
            ProtocolClient.HelperMetadata metadata = transport.protocol().helperMetadata();
            assertEquals("0.1.0-fixture", metadata.helperVersion());
            assertEquals(1, metadata.protocolVersion());
            assertEquals("linux", metadata.os());
            assertEquals("amd64", metadata.arch());
            assertEquals("3.fixture", metadata.sqliteVersion());
            assertTrue(metadata.compileTimeCapabilities().contains("sqliteCompileOptions"));
            assertEquals("pong", transport.protocol().ping().path("op").asText());
            assertEquals("pong", transport.protocol().ping().path("op").asText());
        }
    }

    @Test
    void startupFailureIncludesBoundedStderrDiagnostics() {
        IOException error = assertThrows(IOException.class,
                () -> LocalProcessTransport.start(javaCommand("fail"), config()));

        assertTrue(error.getMessage().contains("Helper startup failed"));
        assertFalse(error.getMessage().contains("hunter2"));
        assertFalse(error.getMessage().contains("/srv/private"));
        assertFalse(error.getMessage().contains("SELECT *"));
    }

    @Test
    void protocolMismatchBreaksConnection() {
        IOException error = assertThrows(IOException.class,
                () -> LocalProcessTransport.start(javaCommand("mismatch"), config()));

        assertTrue(error.getMessage().contains("Helper startup failed"));
    }

    @Test
    void incompatibleHelperVersionBreaksConnection() {
        IOException error = assertThrows(IOException.class,
                () -> LocalProcessTransport.start(javaCommand("badHelperVersion"), config()));

        assertTrue(containsMessage(error, "Unsupported helper version"));
    }

    @Test
    void missingCompileTimeCapabilityBreaksConnection() {
        IOException error = assertThrows(IOException.class,
                () -> LocalProcessTransport.start(javaCommand("missingCapability"), config()));

        assertTrue(containsMessage(error, "required compile-time capability"));
    }

    @Test
    void unsupportedHelperTargetBreaksConnection() {
        IOException error = assertThrows(IOException.class,
                () -> LocalProcessTransport.start(javaCommand("badTarget"), config()));

        assertTrue(containsMessage(error, "Unsupported helper target"));
    }

    @Test
    void eofDuringRequestBreaksConnectionAndSanitizesStderr() throws Exception {
        try (LocalProcessTransport transport = LocalProcessTransport.start(javaCommand("crashOnQuery"), config())) {
            IOException error = assertThrows(IOException.class,
                    () -> transport.protocol().query("SELECT secret FROM private", 0, 1, 1000));

            assertTrue(transport.protocol().isBroken());
            assertFalse(transport.diagnostics().contains("hunter2"));
            assertFalse(transport.diagnostics().contains("/srv/private"));
            assertTrue(error.getMessage() == null || !error.getMessage().contains("SELECT secret"));
        }
    }

    @Test
    void malformedResponseBreaksConnection() throws Exception {
        try (LocalProcessTransport transport = LocalProcessTransport.start(javaCommand("malformedOnQuery"), config())) {
            IOException error = assertThrows(IOException.class,
                    () -> transport.protocol().query("SELECT 1", 0, 1, 1000));

            assertTrue(transport.protocol().isBroken());
            assertTrue(containsMessage(error, "Malformed JSON frame"));
        }
    }

    @Test
    void closeCursorFailureBreaksConnection() throws Exception {
        try (LocalProcessTransport transport = LocalProcessTransport.start(javaCommand("closeCursorFailure"), config())) {
            transport.protocol().query("SELECT 1", 0, 1, 1000);
            assertThrows(IOException.class, () -> transport.protocol().closeCursor("fixture-cursor"));

            assertTrue(transport.protocol().isBroken());
        }
    }

    @Test
    void queryTimeoutBreaksConnection() throws Exception {
        try (LocalProcessTransport transport = LocalProcessTransport.start(javaCommand("hangOnQuery"), config())) {
            IOException error = assertThrows(IOException.class,
                    () -> transport.protocol().query("SELECT 1", 0, 1, 50));

            assertTrue(containsMessage(error, "Timed out waiting for helper response") || error instanceof SocketTimeoutException);
            assertTrue(transport.protocol().isBroken());
        }
    }

    @Test
    void redactorRemovesSensitiveDiagnostics() {
        String text = Redactor.sanitize("jdbc:sshsqlite://u@example.com/srv/db.sqlite password=hunter2 passphrase=open sesame helper.path=/opt/helper SELECT * FROM users WHERE password = 'x'");

        assertFalse(text.contains("hunter2"));
        assertFalse(text.contains("open sesame"));
        assertFalse(text.contains("/srv/db.sqlite"));
        assertFalse(text.contains("/opt/helper"));
        assertFalse(text.contains("SELECT *"));
    }

    @Test
    void diagnosticsBundleIsRedactedByDefault() throws Exception {
        ConnectionConfig config = configWithSecrets();
        LocalProcessTransport transport = LocalProcessTransport.start(javaCommand("normal"), config);
        try (SshSqliteConnection connection = new SshSqliteConnection(config,
                "jdbc:sshsqlite://u@example.com/srv/private/secret.db", transport)) {
            String bundle = connection.diagnosticsBundle("query SELECT * FROM users WHERE password = 'x'");

            assertTrue(bundle.contains("sshsqlite-diagnostics-v1"));
            assertTrue(bundle.contains("0.1.0-fixture"));
            assertFalse(bundle.contains("hunter2"));
            assertFalse(bundle.contains("key-secret"));
            assertFalse(bundle.contains("/srv/private"));
            assertFalse(bundle.contains("/opt/sshsqlite"));
            assertFalse(bundle.contains("SELECT *"));
        }
    }

    @Test
    void statementCancelRemainsUnsupported() throws Exception {
        try (BaseConnection connection = new BaseConnection(); var statement = connection.createStatement()) {
            assertThrows(java.sql.SQLFeatureNotSupportedException.class, statement::cancel);
        }
    }

    private static ConnectionConfig config() {
        Properties props = new Properties();
        props.setProperty("helper.startupTimeoutMs", "5000");
        props.setProperty("stderr.maxBufferedBytes", "128");
        return ConnectionConfig.parse("jdbc:sshsqlite://u@example.com/tmp/test.db", props);
    }

    private static ConnectionConfig configWithSecrets() {
        Properties props = new Properties();
        props.setProperty("helper.startupTimeoutMs", "5000");
        props.setProperty("stderr.maxBufferedBytes", "128");
        props.setProperty("ssh.password", "hunter2");
        props.setProperty("ssh.privateKeyPassphrase", "key-secret");
        props.setProperty("helper.path", "/opt/sshsqlite/bin/helper");
        props.setProperty("db.path", "/srv/private/secret.db");
        return ConnectionConfig.parse("jdbc:sshsqlite://u@example.com/ignored.db", props);
    }

    private static List<String> javaCommand(String mode) {
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("java.home") + "/bin/java");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(FixtureHelper.class.getName());
        command.add(mode);
        return command;
    }

    private static boolean containsMessage(Throwable error, String text) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }
}
