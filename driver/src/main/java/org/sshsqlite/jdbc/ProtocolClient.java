package org.sshsqlite.jdbc;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

final class ProtocolClient implements SqlClient {
    private static final Set<String> REQUIRED_CAPABILITIES = Set.of("query", "open", "cursorFetch", "readonlyAuthorizer");
    private static final Set<String> REQUIRED_COMPILE_TIME_CAPABILITIES = Set.of("cgo", "sqliteVersion", "sqliteCompileOptions", "readonlyAuthorizer");
    private static final Set<String> SUPPORTED_SERVER_TARGETS = Set.of("linux/amd64", "linux/arm64");

    private final FrameCodec codec;
    private final InputStream input;
    private final OutputStream output;
    private final ExecutorService reader;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Object writeLock = new Object();
    private boolean broken;
    private volatile long activeRequestId;
    private String helperVersion = "unknown";
    private String sqliteVersion = "unknown";
    private HelperMetadata helperMetadata = HelperMetadata.unknown();

    ProtocolClient(InputStream input, OutputStream output, int maxFrameBytes) {
        this.input = input;
        this.output = output;
        this.codec = new FrameCodec(maxFrameBytes);
        this.reader = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "sshsqlite-protocol-reader");
            thread.setDaemon(true);
            return thread;
        });
    }

    synchronized JsonNode hello() throws IOException {
        long id = nextId.getAndIncrement();
        Map<String, Object> request = FrameCodec.request(id, "hello");
        request.put("protocolVersion", 1);
        request.put("driverVersion", "0.1.0-phase2");
        request.put("minProtocolVersion", 1);
        JsonNode response = roundTrip(id, "helloAck", request);
        if (response.path("protocolVersion").asInt() != 1) {
            throw broken("Unsupported helper protocol version");
        }
        String reportedHelperVersion = response.path("helperVersion").asText("");
        if (!reportedHelperVersion.startsWith("0.1.")) {
            throw broken("Unsupported helper version: " + reportedHelperVersion);
        }
        String os = response.path("os").asText("");
        String arch = response.path("arch").asText("");
        if (!SUPPORTED_SERVER_TARGETS.contains(os + "/" + arch)) {
            throw broken("Unsupported helper target: " + os + "/" + arch);
        }
        for (String capability : REQUIRED_CAPABILITIES) {
            if (!containsText(response.path("capabilities"), capability)) {
                throw broken("Helper missing required capability: " + capability);
            }
        }
        for (String capability : REQUIRED_COMPILE_TIME_CAPABILITIES) {
            if (!containsText(response.path("compileTimeCapabilities"), capability)) {
                throw broken("Helper missing required compile-time capability: " + capability);
            }
        }
        helperVersion = reportedHelperVersion;
        sqliteVersion = response.path("sqliteVersion").asText("unknown");
        if (sqliteVersion.isBlank()) {
            throw broken("Helper did not report SQLite version");
        }
        helperMetadata = new HelperMetadata(
                helperVersion,
                response.path("protocolVersion").asInt(),
                os,
                arch,
                sqliteVersion,
                response.path("sqliteBinding").asText("unknown"),
                textSet(response.path("capabilities")),
                textSet(response.path("compileTimeCapabilities")),
                textSet(response.path("sqliteCompileOptions")));
        return response;
    }

    synchronized JsonNode open(ConnectionConfig config) throws IOException {
        long id = nextId.getAndIncrement();
        Map<String, Object> request = FrameCodec.request(id, "open");
        request.put("dbPath", config.dbPath);
        request.put("readonly", config.readonly);
        request.put("writeBackupAcknowledged", config.writeBackupAcknowledged);
        request.put("adminSql", config.adminSql);
        request.put("allowSchemaChanges", config.allowSchemaChanges);
        request.put("busyTimeoutMs", config.busyTimeoutMs);
        request.put("queryOnly", config.readonly);
        return roundTrip(id, "openAck", request);
    }

    @Override public synchronized JsonNode ping() throws IOException {
        long id = nextId.getAndIncrement();
        return roundTrip(id, "pong", FrameCodec.request(id, "ping"));
    }

    @Override public synchronized JsonNode query(String sql, int maxRows, int fetchSize, int timeoutMs) throws IOException {
        return query(sql, null, maxRows, fetchSize, timeoutMs);
    }

    @Override public synchronized JsonNode query(String sql, List<Map<String, Object>> params, int maxRows, int fetchSize, int timeoutMs) throws IOException {
        long id = nextId.getAndIncrement();
        Map<String, Object> request = FrameCodec.request(id, "query");
        request.put("sql", sql);
        if (params != null) {
            request.put("params", params);
        }
        request.put("maxRows", Math.max(maxRows, 0));
        request.put("fetchSize", Math.max(fetchSize, 0));
        request.put("timeoutMs", timeoutMs);
        return roundTrip(id, "queryStarted", request, timeoutMs);
    }

    @Override public synchronized JsonNode fetch(String cursorId, int maxRows, int timeoutMs) throws IOException {
        long id = nextId.getAndIncrement();
        Map<String, Object> request = FrameCodec.request(id, "fetch");
        request.put("cursorId", cursorId);
        request.put("maxRows", Math.max(maxRows, 0));
        request.put("timeoutMs", timeoutMs);
        return roundTrip(id, "rowBatch", request, timeoutMs);
    }

    @Override public synchronized JsonNode closeCursor(String cursorId) throws IOException {
        long id = nextId.getAndIncrement();
        Map<String, Object> request = FrameCodec.request(id, "closeCursor");
        request.put("cursorId", cursorId);
        return roundTrip(id, "closeCursorAck", request);
    }

    @Override public synchronized JsonNode exec(String sql, List<Map<String, Object>> params, int timeoutMs) throws IOException {
        long id = nextId.getAndIncrement();
        Map<String, Object> request = FrameCodec.request(id, "exec");
        request.put("sql", sql);
        if (params != null) {
            request.put("params", params);
        }
        request.put("timeoutMs", timeoutMs);
        return roundTrip(id, "execResult", request, timeoutMs);
    }

    @Override public synchronized JsonNode begin(String mode, int timeoutMs) throws IOException {
        long id = nextId.getAndIncrement();
        Map<String, Object> request = FrameCodec.request(id, "begin");
        request.put("mode", mode == null || mode.isBlank() ? "deferred" : mode);
        return roundTrip(id, "beginAck", request, timeoutMs);
    }

    @Override public synchronized JsonNode commit(int timeoutMs) throws IOException {
        long id = nextId.getAndIncrement();
        return roundTrip(id, "commitAck", FrameCodec.request(id, "commit"), timeoutMs);
    }

    @Override public synchronized JsonNode rollback(int timeoutMs) throws IOException {
        long id = nextId.getAndIncrement();
        return roundTrip(id, "rollbackAck", FrameCodec.request(id, "rollback"), timeoutMs);
    }

    @Override public void cancelActive() throws Exception {
        if (!supportsCancellation()) {
            throw new java.sql.SQLFeatureNotSupportedException("Statement.cancel requires helper controlCancel capability");
        }
        long target = activeRequestId;
        if (target <= 0 || broken) {
            return;
        }
        long id = nextId.getAndIncrement();
        Map<String, Object> request = FrameCodec.request(id, "cancel");
        request.put("targetId", target);
        synchronized (writeLock) {
            codec.writeJson(output, request);
        }
    }

    synchronized JsonNode request(String op) throws IOException {
        long id = nextId.getAndIncrement();
        return roundTrip(id, null, FrameCodec.request(id, op));
    }

    @Override public boolean isBroken() {
        return broken;
    }

    @Override public long lastRequestId() { return Math.max(0, nextId.get() - 1); }

    String helperVersion() { return helperVersion; }

    @Override public String sqliteVersion() { return sqliteVersion; }

    @Override public HelperMetadata helperMetadata() { return helperMetadata; }

    boolean supportsCancellation() { return helperMetadata.capabilities().contains("controlCancel"); }

    @Override public void closeReader() { reader.shutdownNow(); }

    @Override public SQLException toSqlException(Exception e) {
        if (broken) {
            return JdbcSupport.brokenConnection("SSHSQLite helper connection is broken", e);
        }
        if (e instanceof ProtocolException && "HYT00".equals(((ProtocolException) e).sqlState())) {
            ProtocolException protocolException = (ProtocolException) e;
            return new java.sql.SQLTimeoutException(protocolException.getMessage(), protocolException.sqlState(), protocolException);
        }
        return new SQLException(e.getMessage(), JdbcSupport.GENERAL_SQL_STATE, e);
    }

    private JsonNode roundTrip(long id, String expectedOp, Object request) throws IOException {
        return roundTrip(id, expectedOp, request, 0);
    }

    private JsonNode roundTrip(long id, String expectedOp, Object request, int timeoutMs) throws IOException {
        if (broken) {
            throw new ProtocolException("Connection is broken");
        }
        JsonNode response;
        try {
            activeRequestId = id;
            synchronized (writeLock) {
                codec.writeJson(output, request);
            }
            do {
                response = readResponse(timeoutMs);
            } while ("cancelAck".equals(response.path("op").asText()));
        } catch (IOException e) {
            broken = true;
            throw e;
        } finally {
            if (activeRequestId == id) {
                activeRequestId = 0;
            }
        }
        if (response.path("id").asLong(-1) != id) {
            throw broken("Response id did not match pending request");
        }
        if (!response.path("ok").asBoolean(false)) {
            JsonNode error = response.path("error");
            if (error.path("connectionBroken").asBoolean(false)) {
                broken = true;
            }
            throw new ProtocolException(Redactor.sanitize(error.path("message").asText("Helper returned error")),
                    error.path("sqlState").asText(null), error.path("connectionBroken").asBoolean(false));
        }
        if (expectedOp != null && !expectedOp.equals(response.path("op").asText())) {
            throw broken("Unexpected response operation: " + Redactor.sanitize(response.path("op").asText()));
        }
        return response;
    }

    private JsonNode readResponse(int timeoutMs) throws IOException {
        if (timeoutMs <= 0) {
            return codec.readJson(input);
        }
        Future<JsonNode> future = reader.submit(() -> codec.readJson(input));
        try {
            return future.get(timeoutMs + 5_000L, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            broken = true;
            future.cancel(true);
            closeStreams();
            SocketTimeoutException timeout = new SocketTimeoutException("Timed out waiting for helper response");
            timeout.initCause(e);
            throw timeout;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            broken = true;
            closeStreams();
            throw new IOException("Interrupted waiting for helper response", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("Unable to read helper response", cause);
        }
    }

    private void closeStreams() {
        try {
            input.close();
        } catch (IOException ignored) {
        }
        try {
            output.close();
        } catch (IOException ignored) {
        }
    }

    private ProtocolException broken(String message) {
        broken = true;
        return new ProtocolException(Redactor.sanitize(message));
    }

    private static boolean containsText(JsonNode array, String value) {
        if (!array.isArray()) {
            return false;
        }
        for (JsonNode node : array) {
            if (value.equals(node.asText())) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> textSet(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        if (array.isArray()) {
            for (JsonNode node : array) {
                values.add(node.asText());
            }
        }
        return Set.copyOf(values);
    }

    static final class HelperMetadata {
        private final String helperVersion;
        private final int protocolVersion;
        private final String os;
        private final String arch;
        private final String sqliteVersion;
        private final String sqliteBinding;
        private final Set<String> capabilities;
        private final Set<String> compileTimeCapabilities;
        private final Set<String> sqliteCompileOptions;

        HelperMetadata(String helperVersion, int protocolVersion, String os, String arch, String sqliteVersion,
                String sqliteBinding, Set<String> capabilities, Set<String> compileTimeCapabilities,
                Set<String> sqliteCompileOptions) {
            this.helperVersion = helperVersion;
            this.protocolVersion = protocolVersion;
            this.os = os;
            this.arch = arch;
            this.sqliteVersion = sqliteVersion;
            this.sqliteBinding = sqliteBinding;
            this.capabilities = capabilities;
            this.compileTimeCapabilities = compileTimeCapabilities;
            this.sqliteCompileOptions = sqliteCompileOptions;
        }

        String helperVersion() { return helperVersion; }
        int protocolVersion() { return protocolVersion; }
        String os() { return os; }
        String arch() { return arch; }
        String sqliteVersion() { return sqliteVersion; }
        String sqliteBinding() { return sqliteBinding; }
        Set<String> capabilities() { return capabilities; }
        Set<String> compileTimeCapabilities() { return compileTimeCapabilities; }
        Set<String> sqliteCompileOptions() { return sqliteCompileOptions; }

        static HelperMetadata unknown() {
            return new HelperMetadata("unknown", -1, "unknown", "unknown", "unknown", "unknown", Set.of(), Set.of(), Set.of());
        }
    }
}
