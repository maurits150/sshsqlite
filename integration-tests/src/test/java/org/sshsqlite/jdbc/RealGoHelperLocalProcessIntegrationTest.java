package org.sshsqlite.jdbc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("legacyHelper")
class RealGoHelperLocalProcessIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void realGoHelperHandlesRepeatedProtocolRequestsOnSameLocalProcess() throws Exception {
        String helperBinary = System.getProperty("sshsqlite.helperBinary");
        assumeTrue(helperBinary != null && !helperBinary.isBlank(), "legacy helper binary property is not set");
        Path db = tempDir.resolve("fixture.db");
        Files.createFile(db);
        Path allowlist = tempDir.resolve("allowlist.json");
        Files.writeString(allowlist,
                "{\n" +
                "  \"version\": 1,\n" +
                "  \"databases\": [\n" +
                "    {\"path\": \"" + jsonEscape(db.toRealPath().toString()) + "\", \"mode\": \"readonly\"}\n" +
                "  ]\n" +
                "}\n");

        try (LocalProcessTransport transport = LocalProcessTransport.start(List.of(
                helperBinary,
                "--stdio",
                "--allowlist",
                allowlist.toString()), config(db))) {
            assertEquals("pong", transport.protocol().ping().path("op").asText());
            assertEquals("pong", transport.protocol().ping().path("op").asText());
            assertFalse(transport.protocol().isBroken());
        }
    }

    private static ConnectionConfig config(Path db) {
        Properties props = new Properties();
        props.setProperty("helper.startupTimeoutMs", "10000");
        props.setProperty("stderr.maxBufferedBytes", "4096");
        return ConnectionConfig.parse("jdbc:sshsqlite://u@example.com" + db, props);
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
