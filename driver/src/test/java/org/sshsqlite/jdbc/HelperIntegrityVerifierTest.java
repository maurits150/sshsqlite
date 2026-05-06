package org.sshsqlite.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HelperIntegrityVerifierTest {
    @Test
    void expectedHashSuccessAndFailure() throws Exception {
        byte[] contents = "helper".getBytes();
        ConnectionConfig ok = config(HelperIntegrityVerifier.sha256(contents), true);
        assertDoesNotThrow(() -> new HelperIntegrityVerifier().verify(ok, access(contents)));

        ConnectionConfig bad = config("00", true);
        assertThrows(IOException.class, () -> new HelperIntegrityVerifier().verify(bad, access(contents)));
    }

    @Test
    void productionFailsClosedWithoutHashOrFileAccess() throws Exception {
        assertThrows(IOException.class, () -> new HelperIntegrityVerifier().verify(config(null, true), access("x".getBytes())));
        assertThrows(IOException.class, () -> new HelperIntegrityVerifier().verify(config("00", true), null));
    }

    @Test
    void productionRejectsWritableHelper() throws Exception {
        ConnectionConfig config = config(HelperIntegrityVerifier.sha256("x".getBytes()), true);
        RemoteFileAccess files = new RemoteFileAccess() {
            @Override
            public RemoteFile stat(String path) {
                return new RemoteFile(path, 0, 0, 0775, 1, true, false);
            }

            @Override
            public byte[] readAll(String path, int maxBytes) {
                return "x".getBytes();
            }
        };

        assertThrows(IOException.class, () -> new HelperIntegrityVerifier().verify(config, files));
    }

    @Test
    void productionRejectsInvalidStatSizeBeforeRead() throws Exception {
        ConnectionConfig config = config(HelperIntegrityVerifier.sha256("x".getBytes()), true);
        RemoteFileAccess files = new RemoteFileAccess() {
            @Override
            public RemoteFile stat(String path) {
                return new RemoteFile(path, 0, 0, 0755, 0, true, false);
            }

            @Override
            public byte[] readAll(String path, int maxBytes) {
                throw new AssertionError("read must not run after invalid stat size");
            }
        };

        assertThrows(IOException.class, () -> new HelperIntegrityVerifier().verify(config, files));
    }

    @Test
    void developmentModeAllowsMissingVerifier() throws Exception {
        assertDoesNotThrow(() -> new HelperIntegrityVerifier().verify(config(null, false), null));
    }

    private static ConnectionConfig config(String hash, boolean production) {
        Properties props = new Properties();
        props.setProperty("helper.path", "/opt/sshsqlite/bin/helper");
        if (hash != null) {
            props.setProperty("helper.expectedSha256", hash);
        }
        if (!production) {
            props.setProperty("developmentMode", "true");
        }
        return ConnectionConfig.parse("jdbc:sshsqlite://u@example.com/db", props);
    }

    private static RemoteFileAccess access(byte[] contents) {
        return new RemoteFileAccess() {
            @Override
            public RemoteFile stat(String path) {
                return new RemoteFile(path, 0, 0, 0755, contents.length, true, false);
            }

            @Override
            public byte[] readAll(String path, int maxBytes) {
                return contents;
            }
        };
    }
}
