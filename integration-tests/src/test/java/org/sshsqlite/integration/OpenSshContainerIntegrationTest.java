package org.sshsqlite.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OpenSshContainerIntegrationTest {
    private static final String IMAGE = "sshsqlite-openssh-fixture:dev";
    private static final String USER = "sshsqlite";
    private static final String PASSWORD = "sshsqlite-password";
    private static final String DB = "/srv/sshsqlite/fixture.db";
    private static boolean imageBuilt;

    @TempDir
    Path tempDir;

    private String containerId;
    private int port;

    @BeforeEach
    void startContainer() throws Exception {
        Assumptions.assumeTrue(commandSucceeds(List.of("docker", "info")), "Docker CLI is unavailable for the OpenSSH fixture");
        buildImageOnce();
        containerId = run(List.of("docker", "run", "-d", "-P", IMAGE)).stdout().trim();
        port = mappedPort(containerId);
        waitForSshd();
    }

    @AfterEach
    void stopContainer() throws Exception {
        if (containerId != null && !containerId.isBlank()) {
            run(List.of("docker", "rm", "-f", containerId));
        }
    }

    @Test
    void jdbcReadOnlyOverOpenSshWithPinnedHostKeyAndPassword() throws Exception {
        installFixtureFiles();
        Path knownHosts = writeKnownHosts(realKnownHostLine());

        try (Connection connection = DriverManager.getConnection(url(), passwordProperties(knownHosts));
             Statement statement = connection.createStatement()) {
            DatabaseMetaData meta = connection.getMetaData();
            assertEquals("SQLite", meta.getDatabaseProductName());
            assertTrue(tableNames(meta).contains("items"));

            try (ResultSet rows = statement.executeQuery("SELECT id, name FROM items ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt("id"));
                assertEquals("alpha", rows.getString("name"));
                assertTrue(rows.next());
                assertEquals("bravo", rows.getString("name"));
            }
        }
    }

    @Test
    void jdbcReadOnlyOverOpenSshWithPinnedHostKeyAndPrivateKey() throws Exception {
        installFixtureFiles();
        Path key = createAuthorizedKey("");
        Path knownHosts = writeKnownHosts(realKnownHostLine());

        Properties props = baseProperties(knownHosts);
        props.setProperty("ssh.privateKey", key.toString());

        try (Connection connection = DriverManager.getConnection(url(), props);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT count(*) AS c FROM items")) {
            assertTrue(rows.next());
            assertEquals(2, rows.getInt("c"));
        }
    }

    @Test
    void jdbcReadOnlyOverOpenSshWithEncryptedPrivateKeyPassphraseProperty() throws Exception {
        installFixtureFiles();
        Path key = createAuthorizedKey("key-passphrase");
        Path knownHosts = writeKnownHosts(realKnownHostLine());

        Properties props = baseProperties(knownHosts);
        props.setProperty("ssh.privateKey", key.toString());
        props.setProperty("ssh.privateKeyPassphrase", "key-passphrase");

        try (Connection connection = DriverManager.getConnection(url(), props);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT name FROM items WHERE id = 2")) {
            assertTrue(rows.next());
            assertEquals("bravo", rows.getString("name"));
        }
    }

    @Test
    void unknownAndChangedHostKeysFailClosed() throws Exception {
        installFixtureFiles();
        Path unknown = tempDir.resolve("unknown_known_hosts");
        Files.writeString(unknown, "");
        assertThrows(SQLException.class, () -> DriverManager.getConnection(url(), passwordProperties(unknown)).close());

        Path changed = writeKnownHosts(changedHostLine(realKnownHostLine()));
        assertThrows(SQLException.class, () -> DriverManager.getConnection(url(), passwordProperties(changed)).close());
    }

    @Test
    void hashedKnownHostsEntryWorksWithOpenSsh() throws Exception {
        installFixtureFiles();
        Path knownHosts = writeKnownHosts(hashedKnownHostLine(realKnownHostLine()));

        try (Connection connection = DriverManager.getConnection(url(), passwordProperties(knownHosts));
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT name FROM items WHERE id = 1")) {
            assertTrue(rows.next());
            assertEquals("alpha", rows.getString("name"));
        }
    }

    @Test
    void revokedHostKeyFailsClosedEvenWithGoodEntryPresent() throws Exception {
        installFixtureFiles();
        String real = realKnownHostLine();
        Path knownHosts = writeKnownHosts(real + System.lineSeparator() + revokedKnownHostLine(real));

        assertThrows(SQLException.class, () -> DriverManager.getConnection(url(), passwordProperties(knownHosts)).close());
    }

    @Test
    void sqlite3PathMismatchFailsBeforeUsableConnection() throws Exception {
        installFixtureFiles();
        Path knownHosts = writeKnownHosts(realKnownHostLine());
        Properties props = passwordProperties(knownHosts);
        props.setProperty("sqlite3.path", "/usr/bin/not-sqlite3");

        assertThrows(SQLException.class, () -> DriverManager.getConnection(url(), props).close());
    }

    private static synchronized void buildImageOnce() throws Exception {
        if (imageBuilt) {
            return;
        }
        Path dir = Files.createTempDirectory("sshsqlite-openssh-image");
        Path dockerfile = dir.resolve("Dockerfile");
        Files.writeString(dockerfile,
                "FROM ubuntu:24.04\n" +
                "RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y openssh-server sqlite3 && rm -rf /var/lib/apt/lists/*\n" +
                "RUN useradd -m -s /bin/bash sshsqlite && echo 'sshsqlite:sshsqlite-password' | chpasswd\n" +
                "RUN ssh-keygen -A && mkdir -p /run/sshd && sed -i 's/^#PasswordAuthentication .*/PasswordAuthentication yes/' /etc/ssh/sshd_config && sed -i 's/^PasswordAuthentication .*/PasswordAuthentication yes/' /etc/ssh/sshd_config\n" +
                "EXPOSE 22\n" +
                "CMD [\"/usr/sbin/sshd\", \"-D\", \"-e\"]\n");
        run(List.of("docker", "build", "-t", IMAGE, dir.toString()));
        imageBuilt = true;
    }

    private void installFixtureFiles() throws Exception {
        Path db = createDatabase();
        execOk("mkdir -p /srv/sshsqlite && chmod 0755 /srv /srv/sshsqlite");
        dockerCp(db, DB);
        execOk("chown root:root " + DB + " && chmod 0444 " + DB);
    }

    private Path createDatabase() throws Exception {
        Path db = tempDir.resolve("fixture.db");
        ProcessResult result = run(List.of("sqlite3", db.toString(), "CREATE TABLE items(id INTEGER PRIMARY KEY, name TEXT NOT NULL); INSERT INTO items VALUES (1, 'alpha'), (2, 'bravo');"));
        assertEquals(0, result.exitCode(), result.stderr());
        return db;
    }

    private Path createAuthorizedKey(String passphrase) throws Exception {
        Path key = tempDir.resolve("id_ed25519");
        run(List.of("ssh-keygen", "-t", "ed25519", "-N", passphrase, "-f", key.toString()));
        execOk("mkdir -p /home/" + USER + "/.ssh");
        dockerCp(Path.of(key + ".pub"), "/home/" + USER + "/.ssh/authorized_keys");
        execOk("chown -R " + USER + ":" + USER + " /home/" + USER + "/.ssh && chmod 0700 /home/" + USER + "/.ssh && chmod 0600 /home/" + USER + "/.ssh/authorized_keys");
        return key;
    }

    private String realKnownHostLine() throws Exception {
        String publicKeys = execOk("cat /etc/ssh/ssh_host_*_key.pub").trim();
        List<String> lines = new ArrayList<>();
        for (String publicKey : publicKeys.split("\\R")) {
            int firstSpace = publicKey.indexOf(' ');
            int secondSpace = publicKey.indexOf(' ', firstSpace + 1);
            String keyPart = secondSpace < 0 ? publicKey : publicKey.substring(0, secondSpace);
            lines.add("[127.0.0.1]:" + port + " " + keyPart);
        }
        return String.join(System.lineSeparator(), lines);
    }

    private String changedHostLine(String realLine) {
        List<String> changed = new ArrayList<>();
        for (String line : realLine.split("\\R")) {
            int last = line.lastIndexOf('A');
            if (last < 0) {
                last = line.length() - 1;
            }
            changed.add(line.substring(0, last) + (line.charAt(last) == 'A' ? 'B' : 'A') + line.substring(last + 1));
        }
        return String.join(System.lineSeparator(), changed);
    }

    private String hashedKnownHostLine(String realLine) throws Exception {
        List<String> hashed = new ArrayList<>();
        for (String line : realLine.split("\\R")) {
            String[] parts = line.split("\\s+", 2);
            hashed.add(hashHost(parts[0]) + " " + parts[1]);
        }
        return String.join(System.lineSeparator(), hashed);
    }

    private String revokedKnownHostLine(String realLine) {
        List<String> revoked = new ArrayList<>();
        for (String line : realLine.split("\\R")) {
            revoked.add("@revoked " + line);
        }
        return String.join(System.lineSeparator(), revoked);
    }

    private String hashHost(String host) throws Exception {
        byte[] salt = "openssh-fixture-salt".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(salt, "HmacSHA1"));
        return "|1|" + Base64.getEncoder().encodeToString(salt) + "|"
                + Base64.getEncoder().encodeToString(mac.doFinal(host.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private Path writeKnownHosts(String line) throws Exception {
        Path knownHosts = tempDir.resolve("known_hosts_" + Math.abs(line.hashCode()));
        Files.writeString(knownHosts, line + System.lineSeparator());
        return knownHosts;
    }

    private String url() {
        return "jdbc:sshsqlite://" + USER + "@127.0.0.1:" + port + DB;
    }

    private Properties passwordProperties(Path knownHosts) throws Exception {
        Properties props = baseProperties(knownHosts);
        props.setProperty("ssh.password", PASSWORD);
        return props;
    }

    private Properties baseProperties(Path knownHosts) throws Exception {
        Properties props = new Properties();
        props.setProperty("ssh.knownHosts", knownHosts.toString());
        props.setProperty("ssh.agent", "false");
        props.setProperty("sqlite3.path", "/usr/bin/sqlite3");
        props.setProperty("sqlite3.startupTimeoutMs", "10000");
        props.setProperty("stderr.maxBufferedBytes", "4096");
        return props;
    }

    private List<String> tableNames(DatabaseMetaData meta) throws Exception {
        List<String> names = new ArrayList<>();
        try (ResultSet tables = meta.getTables(null, "main", "%", null)) {
            while (tables.next()) {
                names.add(tables.getString("TABLE_NAME"));
            }
        }
        return names;
    }

    private void waitForSshd() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (commandSucceeds(List.of("docker", "exec", containerId, "sh", "-lc", "test -s /run/sshd.pid"))) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("sshd did not start in fixture container");
    }

    private int mappedPort(String id) throws Exception {
        String output = run(List.of("docker", "port", id, "22/tcp")).stdout().trim();
        int colon = output.lastIndexOf(':');
        return Integer.parseInt(output.substring(colon + 1));
    }

    private String execOk(String command) throws Exception {
        return run(List.of("docker", "exec", containerId, "sh", "-lc", command)).stdout();
    }

    private void dockerCp(Path source, String target) throws Exception {
        run(List.of("docker", "cp", source.toString(), containerId + ":" + target));
    }

    private static boolean commandSucceeds(List<String> command) throws Exception {
        return run(command, false).exitCode() == 0;
    }

    private static ProcessResult run(List<String> command) throws Exception {
        ProcessResult result = run(command, true);
        assertEquals(0, result.exitCode(), result.stderr());
        return result;
    }

    private static ProcessResult run(List<String> command, boolean check) throws Exception {
        Process process = new ProcessBuilder(command).start();
        byte[] stdout = process.getInputStream().readAllBytes();
        byte[] stderr = process.getErrorStream().readAllBytes();
        int exit = process.waitFor();
        ProcessResult result = new ProcessResult(exit, new String(stdout), new String(stderr));
        if (check && exit != 0) {
            throw new AssertionError("Command failed: " + command + "\n" + result.stderr());
        }
        return result;
    }

    private static final class ProcessResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private ProcessResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        private int exitCode() { return exitCode; }
        private String stdout() { return stdout; }
        private String stderr() { return stderr; }
    }
}
