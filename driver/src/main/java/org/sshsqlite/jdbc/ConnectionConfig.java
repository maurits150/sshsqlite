package org.sshsqlite.jdbc;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

final class ConnectionConfig {
    static final int DEFAULT_PORT = 22;
    static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    static final int DEFAULT_KEEPALIVE_INTERVAL_MS = 30_000;
    static final int DEFAULT_BUSY_TIMEOUT_MS = 1_000;
    static final int DEFAULT_QUERY_TIMEOUT_MS = 30_000;
    static final int DEFAULT_FETCH_SIZE = 200;
    static final int DEFAULT_SQLITE3_STARTUP_TIMEOUT_MS = 10_000;
    static final int DEFAULT_HELPER_STARTUP_TIMEOUT_MS = 10_000;
    static final int DEFAULT_PING_INTERVAL_MS = 30_000;
    static final int DEFAULT_PING_TIMEOUT_MS = 5_000;
    static final int DEFAULT_WRITE_TIMEOUT_MS = 10_000;
    static final int DEFAULT_STDERR_MAX_BYTES = 65_536;
    static final int DEFAULT_MAX_FRAME_BYTES = 1_048_576;
    static final int DEFAULT_CLI_MAX_BUFFERED_RESULT_BYTES = 16 * 1024 * 1024;

    static final String DEFAULT_SQLITE3_PATH = "/usr/bin/sqlite3";
    static final List<String> DEFAULT_PRIVATE_KEY_NAMES = List.of("id_ed25519", "id_ecdsa", "id_rsa", "id_dsa", "identity");

    private static final Set<String> SECRET_KEYS = Set.of("password", "pass", "PWD", "ssh.password", "ssh.privateKey", "privateKey", "privateKeyFile", "keyFile", "identityFile", "sshKey", "ssh.privateKeyPassphrase", "privateKeyPassphrase", "passphrase", "helper.expectedSha256");
    private static final Set<String> PATH_KEYS = Set.of("db.path", "database", "sqlite3.path", "sqlite3Path", "sqlitePath", "helper.path", "helper.localPath", "helper.localAllowlist", "ssh.knownHosts", "knownHosts", "knownHostsFile");
    private static final Map<String, String> PROPERTY_ALIASES = Map.ofEntries(
            Map.entry("user", "ssh.user"),
            Map.entry("username", "ssh.user"),
            Map.entry("UID", "ssh.user"),
            Map.entry("password", "ssh.password"),
            Map.entry("pass", "ssh.password"),
            Map.entry("PWD", "ssh.password"),
            Map.entry("knownHosts", "ssh.knownHosts"),
            Map.entry("knownHostsFile", "ssh.knownHosts"),
            Map.entry("privateKey", "ssh.privateKey"),
            Map.entry("privateKeyFile", "ssh.privateKey"),
            Map.entry("keyFile", "ssh.privateKey"),
            Map.entry("identityFile", "ssh.privateKey"),
            Map.entry("sshKey", "ssh.privateKey"),
            Map.entry("privateKeyPassphrase", "ssh.privateKeyPassphrase"),
            Map.entry("passphrase", "ssh.privateKeyPassphrase"),
            Map.entry("database", "db.path"),
            Map.entry("sqlite3Path", "sqlite3.path"),
            Map.entry("sqlitePath", "sqlite3.path"));

    final String sshHost;
    final int sshPort;
    final String sshUser;
    final Path knownHosts;
    final String privateKey;
    final String privateKeyPassphrase;
    final String password;
    final boolean sshAgent;
    final int connectTimeoutMs;
    final int keepAliveIntervalMs;
    final String dbPath;
    final boolean readonly;
    final boolean writeBackupAcknowledged;
    final boolean adminSql;
    final boolean allowSchemaChanges;
    final String transactionMode;
    final int busyTimeoutMs;
    final int queryTimeoutMs;
    final int fetchSize;
    final String sqlite3Path;
    final String helperPath;
    final String helperTransport;
    final String helperLocalPath;
    final String helperLocalAllowlist;
    final String helperExpectedSha256;
    final int helperExpectedUid;
    final boolean helperAutoUpload;
    final int sqlite3StartupTimeoutMs;
    final int helperStartupTimeoutMs;
    final int pingIntervalMs;
    final int pingTimeoutMs;
    final int writeTimeoutMs;
    final int stderrMaxBufferedBytes;
    final int maxFrameBytes;
    final int cliMaxBufferedResultBytes;
    final boolean productionMode;
    final boolean poolEnabled;
    final int poolMaxSize;
    final int poolMinIdle;
    final int poolIdleTimeoutMs;
    final int poolValidationTimeoutMs;
    final int poolMaxLifetimeMs;
    private final Map<String, String> canonical;

    private ConnectionConfig(Map<String, String> values) {
        this.canonical = Map.copyOf(values);
        this.sshHost = require(values, "ssh.host");
        this.sshPort = intValue(values, "ssh.port", DEFAULT_PORT);
        this.sshUser = require(values, "ssh.user");
        this.knownHosts = Path.of(values.getOrDefault("ssh.knownHosts", defaultKnownHosts()));
        this.privateKey = values.getOrDefault("ssh.privateKey", defaultPrivateKey());
        this.privateKeyPassphrase = values.get("ssh.privateKeyPassphrase");
        this.password = values.get("ssh.password");
        this.sshAgent = boolValue(values, "ssh.agent", false);
        this.connectTimeoutMs = intValue(values, "ssh.connectTimeoutMs", DEFAULT_CONNECT_TIMEOUT_MS);
        this.keepAliveIntervalMs = intValue(values, "ssh.keepAliveIntervalMs", DEFAULT_KEEPALIVE_INTERVAL_MS);
        this.dbPath = require(values, "db.path");
        this.readonly = boolValue(values, "readonly", false);
        this.writeBackupAcknowledged = boolValue(values, "writeBackupAcknowledged", false);
        this.adminSql = boolValue(values, "adminSql", false);
        this.allowSchemaChanges = boolValue(values, "allowSchemaChanges", false);
        this.transactionMode = values.getOrDefault("transactionMode", "deferred");
        if (!Set.of("deferred", "immediate", "exclusive").contains(transactionMode)) {
            throw new IllegalArgumentException("transactionMode must be deferred, immediate, or exclusive");
        }
        this.busyTimeoutMs = intValue(values, "busyTimeoutMs", DEFAULT_BUSY_TIMEOUT_MS);
        this.queryTimeoutMs = intValue(values, "queryTimeoutMs", DEFAULT_QUERY_TIMEOUT_MS);
        this.fetchSize = intValue(values, "fetchSize", DEFAULT_FETCH_SIZE);
        this.sqlite3Path = values.getOrDefault("sqlite3.path", DEFAULT_SQLITE3_PATH);
        this.helperPath = values.get("helper.path");
        this.helperTransport = values.getOrDefault("helper.transport", "ssh");
        this.helperLocalPath = values.get("helper.localPath");
        this.helperLocalAllowlist = values.get("helper.localAllowlist");
        this.helperExpectedSha256 = values.get("helper.expectedSha256");
        this.helperExpectedUid = intValue(values, "helper.expectedUid", 0);
        this.helperAutoUpload = boolValue(values, "helper.autoUpload", false);
        this.sqlite3StartupTimeoutMs = intValue(values, "sqlite3.startupTimeoutMs", DEFAULT_SQLITE3_STARTUP_TIMEOUT_MS);
        this.helperStartupTimeoutMs = intValue(values, "helper.startupTimeoutMs", DEFAULT_HELPER_STARTUP_TIMEOUT_MS);
        this.pingIntervalMs = intValue(values, "protocol.pingIntervalMs", DEFAULT_PING_INTERVAL_MS);
        this.pingTimeoutMs = intValue(values, "protocol.pingTimeoutMs", DEFAULT_PING_TIMEOUT_MS);
        this.writeTimeoutMs = intValue(values, "transport.writeTimeoutMs", DEFAULT_WRITE_TIMEOUT_MS);
        this.stderrMaxBufferedBytes = intValue(values, "stderr.maxBufferedBytes", DEFAULT_STDERR_MAX_BYTES);
        this.maxFrameBytes = intValue(values, "maxFrameBytes", DEFAULT_MAX_FRAME_BYTES);
        this.cliMaxBufferedResultBytes = intValue(values, "cli.maxBufferedResultBytes", DEFAULT_CLI_MAX_BUFFERED_RESULT_BYTES);
        this.productionMode = !boolValue(values, "developmentMode", false);
        this.poolEnabled = boolValue(values, "pool.enabled", false);
        this.poolMaxSize = Math.min(intValue(values, "pool.maxSize", 5), 5);
        this.poolMinIdle = intValue(values, "pool.minIdle", 0);
        this.poolIdleTimeoutMs = intValue(values, "pool.idleTimeoutMs", 300_000);
        this.poolValidationTimeoutMs = intValue(values, "pool.validationTimeoutMs", 5_000);
        this.poolMaxLifetimeMs = intValue(values, "pool.maxLifetimeMs", 1_800_000);
        requirePositive("ssh.port", sshPort);
        requireNonNegative("ssh.connectTimeoutMs", connectTimeoutMs);
        requirePositive("busyTimeoutMs", busyTimeoutMs);
        requirePositive("queryTimeoutMs", queryTimeoutMs);
        requirePositive("fetchSize", fetchSize);
        requirePositive("sqlite3.startupTimeoutMs", sqlite3StartupTimeoutMs);
        requirePositive("helper.startupTimeoutMs", helperStartupTimeoutMs);
        requirePositive("protocol.pingTimeoutMs", pingTimeoutMs);
        requirePositive("stderr.maxBufferedBytes", stderrMaxBufferedBytes);
        requirePositive("maxFrameBytes", maxFrameBytes);
        requirePositive("cli.maxBufferedResultBytes", cliMaxBufferedResultBytes);
        requirePositive("pool.maxSize", poolMaxSize);
        requireNonNegative("pool.minIdle", poolMinIdle);
        requireNonNegative("pool.idleTimeoutMs", poolIdleTimeoutMs);
        requireNonNegative("pool.validationTimeoutMs", poolValidationTimeoutMs);
        requireNonNegative("pool.maxLifetimeMs", poolMaxLifetimeMs);
        if (!Set.of("ssh", "local").contains(helperTransport)) {
            throw new IllegalArgumentException("helper.transport must be ssh or local");
        }
    }

    static ConnectionConfig parse(String url, Properties properties) {
        SshSqliteUrl parsed = SshSqliteUrl.parse(url);
        Map<String, String> values = new TreeMap<>();
        values.putAll(parsed.properties());
        if (properties != null) {
            for (String name : properties.stringPropertyNames()) {
                if (!PropertyNames.KNOWN.contains(name)) {
                    throw new IllegalArgumentException("Unknown connection property: " + name);
                }
                if (!PROPERTY_ALIASES.containsKey(name)) {
                    values.put(name, properties.getProperty(name));
                }
            }
            for (String name : properties.stringPropertyNames()) {
                String canonicalName = PROPERTY_ALIASES.get(name);
                if (canonicalName != null && !properties.containsKey(canonicalName)) {
                    values.put(canonicalName, properties.getProperty(name));
                }
            }
        }
        values.putIfAbsent("ssh.user", System.getProperty("user.name"));
        return new ConnectionConfig(values);
    }

    Map<String, String> redactedProperties() {
        Map<String, String> redacted = new TreeMap<>();
        for (Map.Entry<String, String> entry : canonical.entrySet()) {
            if (SECRET_KEYS.contains(entry.getKey())) {
                redacted.put(entry.getKey(), "<redacted>");
            } else if (PATH_KEYS.contains(entry.getKey())) {
                redacted.put(entry.getKey(), "<path-redacted>");
            } else {
                redacted.put(entry.getKey(), Redactor.sanitize(entry.getValue()));
            }
        }
        return redacted;
    }

    Duration startupTimeout() {
        return Duration.ofMillis(helperStartupTimeoutMs);
    }

    String poolKeyHash() {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, String> entry : new TreeMap<>(canonical).entrySet()) {
                if (entry.getKey().startsWith("pool.")) {
                    continue;
                }
                digest.update(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) '=');
                digest.update(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            byte[] bytes = digest.digest();
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String toString() {
        return "ConnectionConfig" + redactedProperties();
    }

    private static String require(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required property: " + key);
        }
        return value;
    }

    private static int intValue(Map<String, String> values, String key, int defaultValue) {
        String value = values.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer", e);
        }
    }

    private static boolean boolValue(Map<String, String> values, String key, boolean defaultValue) {
        String value = values.get(key);
        if (value == null) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(key + " must be true or false");
    }

    private static void requirePositive(String key, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be positive");
        }
    }

    private static void requireNonNegative(String key, int value) {
        if (value < 0) {
            throw new IllegalArgumentException(key + " must be non-negative");
        }
    }

    private static String defaultKnownHosts() {
        return Path.of(System.getProperty("user.home", ""), ".ssh", "known_hosts").toString();
    }

    private static String defaultPrivateKey() {
        Path sshDir = Path.of(System.getProperty("user.home", ""), ".ssh");
        for (String name : DEFAULT_PRIVATE_KEY_NAMES) {
            Path key = sshDir.resolve(name);
            if (java.nio.file.Files.isRegularFile(key)) {
                return key.toString();
            }
        }
        return null;
    }

    static List<Path> defaultPrivateKeyCandidates() {
        Path sshDir = Path.of(System.getProperty("user.home", ""), ".ssh");
        List<Path> candidates = new java.util.ArrayList<>();
        for (String name : DEFAULT_PRIVATE_KEY_NAMES) {
            candidates.add(sshDir.resolve(name));
        }
        return candidates;
    }

    static final class PropertyNames {
        static final Set<String> KNOWN = Set.of(
                "user", "username", "UID", "password", "pass", "PWD", "knownHosts", "knownHostsFile", "privateKey", "privateKeyFile", "keyFile", "identityFile", "sshKey", "privateKeyPassphrase", "passphrase", "database", "sqlite3Path", "sqlitePath",
                "ssh.host", "ssh.port", "ssh.user", "ssh.knownHosts", "ssh.privateKey", "ssh.privateKeyPassphrase", "ssh.password",
                "ssh.agent", "ssh.connectTimeoutMs", "ssh.keepAliveIntervalMs", "db.path", "readonly",
                "writeBackupAcknowledged", "adminSql", "allowSchemaChanges", "transactionMode", "busyTimeoutMs",
                "queryTimeoutMs", "maxRows", "fetchSize", "sqlite3.path", "sqlite3.startupTimeoutMs", "helper.path", "helper.transport", "helper.localPath", "helper.localAllowlist", "helper.expectedSha256", "helper.autoUpload",
                "helper.expectedUid", "helper.startupTimeoutMs", "protocol.pingIntervalMs", "protocol.pingTimeoutMs", "transport.writeTimeoutMs",
                "stderr.maxBufferedBytes", "cli.maxBufferedResultBytes", "pool.enabled", "pool.maxSize", "pool.minIdle", "pool.idleTimeoutMs",
                "pool.validationTimeoutMs", "pool.maxLifetimeMs", "param.dotCommands", "log.level", "trace.protocol",
                "maxFrameBytes", "developmentMode");

        private PropertyNames() {
        }
    }
}
