package org.sshsqlite.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class CliProtocolClient implements SqlClient {
    private static final String LEGACY_NULL_SENTINEL = "__SSHSQLITE_NULL__";

    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final ExecutorService executor;
    private final Map<String, Cursor> cursors = new LinkedHashMap<>();
    private final ProtocolClient.HelperMetadata metadata;
    private final BoundedCapture stderr;
    private final Runnable abortBackend;
    private final int maxBufferedResultBytes;
    private final String nullSentinel = "__SSN" + Integer.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextInt());
    private boolean broken;
    private boolean aborted;
    private long nextId = 1;

    CliProtocolClient(InputStream input, OutputStream output, ConnectionConfig config, BoundedCapture stderr) throws IOException {
        this(input, output, config, stderr, null);
    }

    CliProtocolClient(InputStream input, OutputStream output, ConnectionConfig config, BoundedCapture stderr, Runnable abortBackend) throws IOException {
        this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        this.stderr = stderr;
        this.abortBackend = abortBackend;
        this.maxBufferedResultBytes = config.cliMaxBufferedResultBytes;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "sshsqlite-cli-client");
            thread.setDaemon(true);
            return thread;
        });
        String version;
        try {
            setup(config);
            version = readVersion(config.queryTimeoutMs);
        } catch (IOException e) {
            String stderrText = stderrSince(0);
            throw statementError("sqlite3 CLI startup failed"
                    + (stderrText.isBlank() ? "" : ": stderr=" + diagnosticSnippet(stderrText)), e);
        }
        this.metadata = new ProtocolClient.HelperMetadata(
                "sqlite3-cli", 0, "unknown", "unknown", version, "sqlite3 CLI",
                Set.of("query", "open", "cursorFetch"), Set.of("sqliteVersion"), Set.of());
    }

    @Override
    public synchronized JsonNode ping() throws IOException {
        JsonNode rows = runJson("SELECT 1 AS ok", 5_000);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("op", "pong");
        out.put("ok", rows.isArray() && rows.size() == 1);
        return out;
    }

    @Override
    public synchronized JsonNode query(String sql, int maxRows, int fetchSize, int timeoutMs) throws IOException {
        return query(sql, null, maxRows, fetchSize, timeoutMs);
    }

    @Override
    public synchronized JsonNode query(String sql, List<Map<String, Object>> params, int maxRows, int fetchSize, int timeoutMs) throws IOException {
        String rendered = renderSql(sql, params);
        List<String> statements = splitSqlStatements(rendered);
        ParsedCsv parsed = statements.size() > 1 && containsExplicitTransactionControl(statements)
                ? runExplicitTransactionQueryScript(rendered, timeoutMs)
                : runQuoted(maybeLimitRows(rendered, maxRows), timeoutMs);
        List<String> names = parsed.headers.isEmpty() ? columnNamesFromSelect(rendered) : parsed.headers;
        ArrayDeque<List<JsonNode>> decodedRows = new ArrayDeque<>();
        for (List<JsonNode> row : parsed.rows) {
            List<JsonNode> values = new ArrayList<>();
            for (JsonNode value : row) {
                values.add(isEncodedProtocolValue(value) ? value : encodeJsonValue(value));
            }
            decodedRows.add(values);
            if (maxRows > 0 && decodedRows.size() >= maxRows) {
                break;
            }
        }
        String cursorId = UUID.randomUUID().toString();
        cursors.put(cursorId, new Cursor(names, decodedRows));
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("op", "queryStarted");
        out.put("ok", true);
        out.put("id", nextId++);
        out.put("cursorId", cursorId);
        ArrayNode columns = out.putArray("columns");
        for (int i = 0; i < names.size(); i++) {
            ObjectNode column = columns.addObject();
            column.put("name", names.get(i));
            column.put("declaredType", "");
            column.put("sqliteType", inferredSqliteType(parsed.rows, i));
            column.put("nullable", true);
        }
        return out;
    }

    @Override
    public synchronized JsonNode fetch(String cursorId, int maxRows, int timeoutMs) throws IOException {
        Cursor cursor = cursors.get(cursorId);
        if (cursor == null) {
            throw new IOException("Unknown cursor");
        }
        int limit = maxRows <= 0 ? Integer.MAX_VALUE : maxRows;
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("op", "rowBatch");
        out.put("ok", true);
        out.put("id", nextId++);
        ArrayNode rows = out.putArray("rows");
        int count = 0;
        while (count < limit && !cursor.rows.isEmpty()) {
            ArrayNode row = rows.addArray();
            for (JsonNode value : cursor.rows.removeFirst()) {
                row.add(value);
            }
            count++;
        }
        boolean done = cursor.rows.isEmpty();
        out.put("done", done);
        if (done) {
            cursors.remove(cursorId);
        }
        return out;
    }

    @Override
    public synchronized JsonNode closeCursor(String cursorId) {
        cursors.remove(cursorId);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("op", "closeCursorAck");
        out.put("ok", true);
        out.put("id", nextId++);
        return out;
    }

    @Override
    public synchronized JsonNode exec(String sql, List<Map<String, Object>> params, int timeoutMs) throws IOException {
        String rendered = renderSql(sql, params);
        runExecScript(rendered, timeoutMs);
        boolean returnsChanges = lastStatementReturnsChanges(rendered);
        JsonNode rows = runJson(returnsChanges
                ? "SELECT changes() AS changes, last_insert_rowid() AS lastInsertRowid"
                : "SELECT 0 AS changes, last_insert_rowid() AS lastInsertRowid", timeoutMs);
        JsonNode row = rows.size() == 0 ? JsonNodeFactory.instance.objectNode() : rows.get(0);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("op", "execResult");
        out.put("ok", true);
        out.put("id", nextId++);
        out.put("changes", row.path("changes").asLong(0));
        out.put("lastInsertRowid", row.path("lastInsertRowid").asLong(0));
        return out;
    }

    @Override public JsonNode begin(String mode, int timeoutMs) throws IOException { runNoRows("BEGIN " + transactionMode(mode), timeoutMs); return ack("beginAck"); }
    @Override public JsonNode commit(int timeoutMs) throws IOException { runNoRows("COMMIT", timeoutMs); return ack("commitAck"); }
    @Override public JsonNode rollback(int timeoutMs) throws IOException { runNoRows("ROLLBACK", timeoutMs); return ack("rollbackAck"); }

    @Override
    public void cancelActive() throws Exception {
        throw new java.sql.SQLFeatureNotSupportedException("Statement.cancel is not supported by sqlite3 CLI mode");
    }

    @Override public boolean isBroken() { return broken; }
    @Override public long lastRequestId() { return nextId - 1; }
    @Override public String sqliteVersion() { return metadata.sqliteVersion(); }
    @Override public ProtocolClient.HelperMetadata helperMetadata() { return metadata; }
    @Override public void closeReader() {
        try {
            writer.close();
        } catch (IOException ignored) {
        }
        try {
            reader.close();
        } catch (IOException ignored) {
        }
        executor.shutdownNow();
    }

    @Override
    public SQLException toSqlException(Exception e) {
        if (e instanceof SocketTimeoutException) {
            return new java.sql.SQLTimeoutException(e.getMessage(), "HYT00", e);
        }
        if (isUnsupportedDropColumn(e)) {
            return new SQLFeatureNotSupportedException("ALTER TABLE ... DROP COLUMN requires SQLite 3.35.0 or newer; remote sqlite3 is "
                    + metadata.sqliteVersion(), "0A000", e);
        }
        FeatureGate featureGate = unsupportedFeature(e);
        if (featureGate != null) {
            return new SQLFeatureNotSupportedException(featureGate.description + " requires SQLite " + featureGate.minimumVersion
                    + " or newer; remote sqlite3 is " + metadata.sqliteVersion(), "0A000", e);
        }
        if (isLockContention(e)) {
            return new java.sql.SQLTransientException("SQLite lock contention: " + e.getMessage(), "HYT00", e);
        }
        if (broken) {
            return JdbcSupport.brokenConnection("SSHSQLite sqlite3 CLI connection is broken", e);
        }
        return new SQLException(e.getMessage(), JdbcSupport.GENERAL_SQL_STATE, e);
    }

    private boolean isUnsupportedDropColumn(Exception e) {
        if (compareSqliteVersion(metadata.sqliteVersion(), "3.35.0") >= 0) {
            return false;
        }
        String message = e.getMessage();
        if (message == null) return false;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("near \"drop\": syntax error") || lower.contains("near \"drop\"");
    }

    private FeatureGate unsupportedFeature(Exception e) {
        String message = e.getMessage();
        if (message == null) return null;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (!lower.contains("syntax error")) return null;
        if (compareSqliteVersion(metadata.sqliteVersion(), "3.35.0") < 0 && lower.contains("near \"returning\"")) {
            return new FeatureGate("RETURNING", "3.35.0");
        }
        if (compareSqliteVersion(metadata.sqliteVersion(), "3.31.0") < 0 && lower.contains("near \"generated\"")) {
            return new FeatureGate("Generated columns", "3.31.0");
        }
        if (compareSqliteVersion(metadata.sqliteVersion(), "3.37.0") < 0 && lower.contains("near \"strict\"")) {
            return new FeatureGate("STRICT tables", "3.37.0");
        }
        if (compareSqliteVersion(metadata.sqliteVersion(), "3.33.0") < 0 && lower.contains("near \"from\"")) {
            return new FeatureGate("UPDATE ... FROM", "3.33.0");
        }
        if (compareSqliteVersion(metadata.sqliteVersion(), "3.39.0") < 0 && (lower.contains("near \"right\"") || lower.contains("near \"full\""))) {
            return new FeatureGate("RIGHT/FULL OUTER JOIN", "3.39.0");
        }
        if (compareSqliteVersion(metadata.sqliteVersion(), "3.30.0") < 0 && (lower.contains("near \"nulls\"") || lower.contains("near \"first\"") || lower.contains("near \"last\""))) {
            return new FeatureGate("NULLS FIRST/LAST", "3.30.0");
        }
        return null;
    }

    private static final class FeatureGate {
        final String description;
        final String minimumVersion;
        FeatureGate(String description, String minimumVersion) {
            this.description = description;
            this.minimumVersion = minimumVersion;
        }
    }

    private static int compareSqliteVersion(String left, String right) {
        int[] a = parseVersion(left);
        int[] b = parseVersion(right);
        for (int i = 0; i < 3; i++) {
            int compare = Integer.compare(a[i], b[i]);
            if (compare != 0) return compare;
        }
        return 0;
    }

    private static int[] parseVersion(String value) {
        int[] result = new int[3];
        if (value == null) return result;
        String[] parts = value.split("\\.");
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            try {
                result[i] = Integer.parseInt(parts[i].replaceAll("[^0-9].*", ""));
            } catch (NumberFormatException ignored) {
                result[i] = 0;
            }
        }
        return result;
    }

    private static boolean isLockContention(Exception e) {
        String message = e.getMessage();
        if (message == null) return false;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("database is locked")
                || lower.contains("database is busy")
                || lower.contains("database table is locked")
                || lower.contains("sqlite_busy")
                || lower.contains("sqlite_locked");
    }

    private void setup(ConnectionConfig config) throws IOException {
        writeLine(".bail off");
        writeLine(".headers on");
        writeLine(".mode csv");
        writeLine(".nullvalue " + nullSentinel);
        writeLine(".timeout " + config.busyTimeoutMs);
        if (config.readonly) {
            writeSql("PRAGMA query_only=ON");
        }
    }

    private String readVersion(int timeoutMs) throws IOException {
        JsonNode rows = runJson("SELECT sqlite_version() AS version", timeoutMs);
        if (!rows.isArray() || rows.size() != 1) {
            throw new IOException("Unable to validate sqlite3 CLI startup");
        }
        return rows.get(0).path("version").asText("unknown");
    }

    private JsonNode runJson(String sql, int timeoutMs) throws IOException {
        String output = run(sql, timeoutMs);
        try {
            return parseCsvRows(output);
        } catch (IOException e) {
            markBrokenAndAbort();
            String stderrText = stderr == null ? "" : stderr.text();
            throw statementError("Unable to parse sqlite3 output. stdout=" + diagnosticSnippet(output)
                    + (stderrText.isBlank() ? "" : " stderr=" + diagnosticSnippet(stderrText)), e);
        }
    }

    private ParsedCsv runCsv(String sql, int timeoutMs) throws IOException {
        String output = run(sql, timeoutMs);
        try {
            return parseCsvOutput(output);
        } catch (IOException e) {
            markBrokenAndAbort();
            String stderrText = stderr == null ? "" : stderr.text();
            throw statementError("Unable to parse sqlite3 output. stdout=" + diagnosticSnippet(output)
                    + (stderrText.isBlank() ? "" : " stderr=" + diagnosticSnippet(stderrText)), e);
        }
    }

    private ParsedCsv runQuoted(String sql, int timeoutMs) throws IOException {
        String output = run(sql, timeoutMs, true);
        try {
            return parseQuotedOutput(output);
        } catch (IOException e) {
            markBrokenAndAbort();
            String stderrText = stderr == null ? "" : stderr.text();
            throw statementError("Unable to parse sqlite3 output. stdout=" + diagnosticSnippet(output)
                    + (stderrText.isBlank() ? "" : " stderr=" + diagnosticSnippet(stderrText)), e);
        }
    }

    private void runNoRows(String sql, int timeoutMs) throws IOException {
        String output = run(sql, timeoutMs, false);
        if (!output.isBlank()) {
            throw statementError("sqlite3 update produced unexpected result output");
        }
    }

    private void runExecScript(String sql, int timeoutMs) throws IOException {
        List<String> statements = splitSqlStatements(sql);
        if (statements.size() <= 1) {
            runNoRows(sql, timeoutMs);
            return;
        }
        if (containsExplicitTransactionControl(statements)) {
            int completedStatements = 0;
            for (String statement : statements) {
                try {
                    runNoRows(statement, timeoutMs);
                    completedStatements++;
                } catch (IOException e) {
                    throw new ScriptExecutionException(e.getMessage(), e, completedStatements);
                }
            }
            return;
        }
        String savepoint = "sshsqlite_script_" + UUID.randomUUID().toString().replace('-', '_');
        boolean started = false;
        try {
            runNoRows("SAVEPOINT " + savepoint, timeoutMs);
            started = true;
            for (String statement : statements) {
                runNoRows(statement, timeoutMs);
            }
            runNoRows("RELEASE " + savepoint, timeoutMs);
            started = false;
        } catch (IOException e) {
            if (started && !broken) {
                try {
                    runNoRows("ROLLBACK TO " + savepoint, timeoutMs);
                } catch (IOException ignored) {
                    // Keep the original sqlite3 error; cleanup is best-effort on an already failed script.
                }
                try {
                    runNoRows("RELEASE " + savepoint, timeoutMs);
                } catch (IOException ignored) {
                    // Releasing after rollback can fail if sqlite3 already unwound the savepoint.
                }
            }
            throw e;
        }
    }

    private String run(String sql, int timeoutMs) throws IOException {
        return run(sql, timeoutMs, false);
    }

    private String run(String sql, int timeoutMs, boolean quoteMode) throws IOException {
        ensureSafeSql(sql);
        String marker = "__SSHSQLITE_DONE_" + UUID.randomUUID().toString().replace('-', '_') + "__";
        long stderrStart = stderr == null ? 0 : stderr.position();
        Future<String> future = executor.submit(readUntil(marker));
        if (quoteMode) {
            writeLine(".mode quote");
        }
        writeSql(sql);
        if (quoteMode) {
            writeLine(".mode csv");
        }
        writeLine(".print " + marker);
        try {
            String output = timeoutMs <= 0 ? future.get() : future.get(timeoutMs, TimeUnit.MILLISECONDS);
            String statementStderr = stderrSince(stderrStart);
            if (!statementStderr.isBlank()) {
                throw statementError(statementStderr);
            }
            return output;
        } catch (TimeoutException e) {
            markBrokenAndAbort();
            future.cancel(true);
            SocketTimeoutException timeout = new SocketTimeoutException("Timed out waiting for sqlite3 CLI response");
            timeout.initCause(e);
            throw timeout;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markBrokenAndAbort();
            throw new IOException("Interrupted waiting for sqlite3 CLI response", e);
        } catch (java.util.concurrent.ExecutionException e) {
            markBrokenAndAbort();
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("Unable to read sqlite3 CLI output", cause);
        }
    }

    private ParsedCsv runExplicitTransactionQueryScript(String sql, int timeoutMs) throws IOException {
        List<String> statements = splitSqlStatements(sql);
        int completedStatements = 0;
        ParsedCsv result = null;
        try {
            for (String statement : statements) {
                if (SshSqliteStatement.returnsRows(statement)) {
                    if (result != null) {
                        throw statementError("Multiple row-producing SQL statements are not supported by this JDBC driver");
                    }
                    result = runQuoted(statement, timeoutMs);
                } else {
                    runNoRows(statement, timeoutMs);
                }
                completedStatements++;
            }
            if (result == null) {
                throw statementError("executeQuery requires a statement that returns rows");
            }
            return result;
        } catch (IOException e) {
            throw new ScriptExecutionException(e.getMessage(), e, completedStatements);
        }
    }

    private String stderrSince(long start) {
        if (stderr == null) {
            return "";
        }
        String previous = "";
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100L);
        while (true) {
            String text = stderr.textSince(start);
            if (!text.equals(previous)) {
                previous = text;
                deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25L);
            }
            if (System.nanoTime() >= deadline) {
                return text;
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return text;
            }
        }
    }

    private Callable<String> readUntil(String marker) {
        return () -> {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (marker.equals(line)) {
                    return out.toString().trim();
                }
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(line);
                if (out.length() > maxBufferedResultBytes) {
                    markBrokenAndAbort();
                    throw new IOException("sqlite3 CLI result exceeded cli.maxBufferedResultBytes=" + maxBufferedResultBytes);
                }
            }
            String stderrText = stderrSince(0);
            markBrokenAndAbort();
            throw statementError("sqlite3 CLI exited while waiting for response"
                    + (stderrText.isBlank() ? "" : ": stderr=" + diagnosticSnippet(stderrText)));
        };
    }

    private void markBrokenAndAbort() {
        broken = true;
        if (!aborted) {
            aborted = true;
            try {
                writer.close();
            } catch (IOException ignored) {
            }
            try {
                reader.close();
            } catch (IOException ignored) {
            }
            executor.shutdownNow();
            if (abortBackend != null) {
                abortBackend.run();
            }
        }
    }

    private void writeSql(String sql) throws IOException {
        writeLine(ensureSemicolon(sql));
    }

    private void writeLine(String line) throws IOException {
        try {
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            markBrokenAndAbort();
            throw e;
        }
    }

    private IOException statementError(String message) {
        return new IOException(Redactor.sanitize(message));
    }

    private IOException statementError(String message, Exception cause) {
        return new IOException(Redactor.sanitize(message), cause);
    }

    private static String diagnosticSnippet(String value) {
        String sanitized = Redactor.sanitize(value == null ? "" : value).replace("\r", "\\r").replace("\n", "\\n");
        int max = 4096;
        if (sanitized.length() > max) {
            return sanitized.substring(0, max) + "...(truncated, " + sanitized.length() + " chars)";
        }
        return sanitized;
    }

    private ObjectNode ack(String op) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("op", op);
        out.put("ok", true);
        out.put("id", nextId++);
        return out;
    }

    private static String ensureSemicolon(String sql) {
        String trimmed = sql.trim();
        return trimmed.endsWith(";") ? trimmed : trimmed + ";";
    }

    private static void ensureSafeSql(String sql) throws IOException {
        if (containsDotCommandLine(sql)) {
            throw new IOException("SQLite dot commands are not allowed");
        }
    }

    private static boolean containsDotCommandLine(String sql) {
        boolean atLineStart = true;
        boolean lineComment = false;
        boolean blockComment = false;
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '\r' || ch == '\n') {
                atLineStart = true;
                lineComment = false;
                continue;
            }
            if (lineComment) {
                continue;
            }
            if (blockComment) {
                if (ch == '/' && i > 0 && sql.charAt(i - 1) == '*') blockComment = false;
                continue;
            }
            if (quote != 0) {
                if ((quote == '[' && ch == ']') || (quote != '[' && ch == quote)) {
                    if (quote == '\'' && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (atLineStart && Character.isWhitespace(ch)) {
                continue;
            }
            if (atLineStart && ch == '.') {
                return true;
            }
            atLineStart = false;
            if (ch == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                lineComment = true;
                i++;
            } else if (ch == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                blockComment = true;
                i++;
            } else if (ch == '\'' || ch == '"' || ch == '`' || ch == '[') {
                quote = ch;
            }
        }
        return false;
    }

    private static String transactionMode(String mode) throws IOException {
        String normalized = mode == null || mode.isBlank() ? "deferred" : mode.toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("deferred", "immediate", "exclusive").contains(normalized)) {
            throw new IOException("Unsupported transaction mode");
        }
        return normalized.toUpperCase(java.util.Locale.ROOT);
    }

    private static JsonNode encodeJsonValue(JsonNode value) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        if (value == null || value.isNull()) {
            out.put("type", "null");
        } else if (value.isIntegralNumber()) {
            out.put("type", "integer");
            out.put("value", value.asLong());
        } else if (value.isFloatingPointNumber()) {
            out.put("type", "real");
            out.put("value", value.asDouble());
        } else {
            out.put("type", "text");
            out.put("value", value.asText());
        }
        return out;
    }

    private JsonNode parseCsvRows(String output) throws IOException {
        ParsedCsv parsed = parseCsvOutput(output);
        ArrayNode rows = JsonNodeFactory.instance.arrayNode();
        for (List<JsonNode> values : parsed.rows) {
            ObjectNode row = rows.addObject();
            for (int column = 0; column < parsed.headers.size(); column++) {
                row.set(parsed.headers.get(column), values.get(column));
            }
        }
        return rows;
    }

    private ParsedCsv parseCsvOutput(String output) throws IOException {
        if (output == null || output.isBlank()) {
            return new ParsedCsv(List.of(), List.of());
        }
        List<List<String>> records = parseCsv(output);
        if (records.isEmpty()) {
            return new ParsedCsv(List.of(), List.of());
        }
        List<String> headers = records.get(0);
        List<List<JsonNode>> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> record = records.get(i);
            if (record.size() != headers.size()) {
                throw new IOException("sqlite3 CSV row had " + record.size() + " columns, expected " + headers.size());
            }
            List<JsonNode> row = new ArrayList<>();
            for (String value : record) {
                row.add(csvValue(value));
            }
            rows.add(row);
        }
        return new ParsedCsv(headers, rows);
    }

    private JsonNode csvValue(String value) {
        if (nullSentinel.equals(value)) {
            return JsonNodeFactory.instance.nullNode();
        } else if (value.matches("-?(0|[1-9][0-9]*)")) {
            try {
                return JsonNodeFactory.instance.numberNode(Long.parseLong(value));
            } catch (NumberFormatException ignored) {
                return JsonNodeFactory.instance.textNode(value);
            }
        } else if (value.matches("-?((([0-9]+)\\.([0-9]*))|(([0-9]*)\\.([0-9]+)))([eE][+-]?[0-9]+)?|-?[0-9]+[eE][+-]?[0-9]+")) {
            try {
                return JsonNodeFactory.instance.numberNode(Double.parseDouble(value));
            } catch (NumberFormatException ignored) {
                return JsonNodeFactory.instance.textNode(value);
            }
        } else {
            return JsonNodeFactory.instance.textNode(value);
        }
    }

    private static ParsedCsv parseQuotedOutput(String output) throws IOException {
        if (output == null || output.isBlank()) {
            return new ParsedCsv(List.of(), List.of());
        }
        List<List<JsonNode>> records = parseQuotedRecords(output);
        if (records.isEmpty()) {
            return new ParsedCsv(List.of(), List.of());
        }
        List<String> headers = new ArrayList<>();
        for (JsonNode header : records.get(0)) {
            headers.add(header.asText());
        }
        List<List<JsonNode>> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<JsonNode> record = records.get(i);
            if (record.size() != headers.size()) {
                throw new IOException("sqlite3 quoted row had " + record.size() + " columns, expected " + headers.size());
            }
            rows.add(record);
        }
        return new ParsedCsv(headers, rows);
    }

    private static List<List<JsonNode>> parseQuotedRecords(String text) throws IOException {
        List<List<JsonNode>> records = new ArrayList<>();
        List<JsonNode> record = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (ch == '\r' || ch == '\n') {
                i = consumeRecordSeparator(text, i);
                if (!record.isEmpty()) {
                    records.add(record);
                    record = new ArrayList<>();
                }
                continue;
            }
            Field field = parseQuotedField(text, i);
            record.add(field.value);
            i = field.next;
            if (i >= text.length()) {
                break;
            }
            ch = text.charAt(i);
            if (ch == ',') {
                i++;
            } else if (ch == '\r' || ch == '\n') {
                i = consumeRecordSeparator(text, i);
                records.add(record);
                record = new ArrayList<>();
            } else {
                throw new IOException("Unexpected sqlite3 quote-mode character: " + ch);
            }
        }
        if (!record.isEmpty()) {
            records.add(record);
        }
        return records;
    }

    private static Field parseQuotedField(String text, int start) throws IOException {
        if (startsWithBlobLiteral(text, start)) {
            int i = start + 2;
            StringBuilder hex = new StringBuilder();
            while (i < text.length()) {
                char ch = text.charAt(i++);
                if (ch == '\'') {
                    ObjectNode blob = JsonNodeFactory.instance.objectNode();
                    blob.put("type", "blob");
                    blob.put("base64", Base64.getEncoder().encodeToString(bytesFromHex(hex.toString())));
                    return new Field(blob, i);
                }
                hex.append(ch);
            }
            throw new IOException("Unterminated sqlite3 blob literal");
        }
        if (start < text.length() && text.charAt(start) == '\'') {
            StringBuilder value = new StringBuilder();
            int i = start + 1;
            while (i < text.length()) {
                char ch = text.charAt(i++);
                if (ch == '\'') {
                    if (i < text.length() && text.charAt(i) == '\'') {
                        value.append('\'');
                        i++;
                    } else {
                        return new Field(JsonNodeFactory.instance.textNode(value.toString()), i);
                    }
                } else {
                    value.append(ch);
                }
            }
            throw new IOException("Unterminated sqlite3 text literal");
        }
        int end = start;
        while (end < text.length() && text.charAt(end) != ',' && text.charAt(end) != '\n' && text.charAt(end) != '\r') {
            end++;
        }
        String value = text.substring(start, end).trim();
        if (value.equalsIgnoreCase("NULL")) {
            return new Field(JsonNodeFactory.instance.nullNode(), end);
        }
        if (value.matches("-?(0|[1-9][0-9]*)")) {
            try {
                return new Field(JsonNodeFactory.instance.numberNode(Long.parseLong(value)), end);
            } catch (NumberFormatException ignored) {
                return new Field(JsonNodeFactory.instance.textNode(value), end);
            }
        }
        if (value.matches("-?((([0-9]+)\\.([0-9]*))|(([0-9]*)\\.([0-9]+)))([eE][+-]?[0-9]+)?|-?[0-9]+[eE][+-]?[0-9]+")) {
            try {
                return new Field(JsonNodeFactory.instance.numberNode(Double.parseDouble(value)), end);
            } catch (NumberFormatException ignored) {
                return new Field(JsonNodeFactory.instance.textNode(value), end);
            }
        }
        return new Field(JsonNodeFactory.instance.textNode(value), end);
    }

    private static int consumeRecordSeparator(String text, int index) {
        if (text.charAt(index) == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
            return index + 2;
        }
        return index + 1;
    }

    private static boolean startsWithBlobLiteral(String text, int start) {
        return start + 1 < text.length() && (text.charAt(start) == 'X' || text.charAt(start) == 'x') && text.charAt(start + 1) == '\'';
    }

    private static byte[] bytesFromHex(String hex) throws IOException {
        if ((hex.length() & 1) != 0) {
            throw new IOException("Odd-length sqlite3 blob literal");
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IOException("Invalid sqlite3 blob literal");
            }
            bytes[i] = (byte) ((hi << 4) | lo);
        }
        return bytes;
    }

    private static boolean isEncodedProtocolValue(JsonNode value) {
        return value != null && value.isObject() && value.has("type") && (value.has("value") || value.has("base64") || "null".equals(value.path("type").asText()));
    }

    private static String maybeLimitRows(String sql, int maxRows) {
        if (maxRows <= 0 || !isSimpleRowQuery(sql)) {
            return sql;
        }
        return "SELECT * FROM (" + trimTrailingSemicolon(sql.trim()) + ") LIMIT " + maxRows;
    }

    private static boolean isSimpleRowQuery(String sql) {
        String trimmed = stripLeadingCommentsAndWhitespace(sql == null ? "" : sql).trim();
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (!(lower.startsWith("select") || lower.startsWith("with") || lower.startsWith("values"))) {
            return false;
        }
        if (lower.startsWith("with") && containsWriteKeywordBeforeReturning(sqlTokens(trimmed))) {
            return false;
        }
        return !hasStatementSeparatorBeforeEnd(trimmed);
    }

    private static boolean containsWriteKeywordBeforeReturning(List<String> tokens) {
        for (String token : tokens) {
            String lower = token.toLowerCase(java.util.Locale.ROOT);
            if (lower.equals("returning")) {
                return false;
            }
            if (lower.equals("insert") || lower.equals("update") || lower.equals("delete") || lower.equals("replace")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> sqlTokens(String sql) {
        ArrayList<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean lineComment = false;
        boolean blockComment = false;
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (lineComment) {
                if (ch == '\n' || ch == '\r') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (ch == '/' && i > 0 && sql.charAt(i - 1) == '*') blockComment = false;
                continue;
            }
            if (quote != 0) {
                if ((quote == '[' && ch == ']') || (quote != '[' && ch == quote)) {
                    if (quote == '\'' && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        i++;
                        continue;
                    }
                    quote = 0;
                }
                continue;
            }
            if (ch == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                flushToken(tokens, current);
                lineComment = true;
                i++;
                continue;
            }
            if (ch == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                flushToken(tokens, current);
                blockComment = true;
                i++;
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`' || ch == '[') {
                flushToken(tokens, current);
                quote = ch;
                continue;
            }
            if (Character.isLetterOrDigit(ch) || ch == '_') {
                current.append(ch);
            } else {
                flushToken(tokens, current);
            }
        }
        flushToken(tokens, current);
        return tokens;
    }

    private static void flushToken(List<String> tokens, StringBuilder current) {
        if (current.length() > 0) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }

    private static String stripLeadingCommentsAndWhitespace(String sql) {
        int i = 0;
        while (i < sql.length()) {
            while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
                i++;
            }
            if (i + 1 < sql.length() && sql.charAt(i) == '-' && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
                continue;
            }
            if (i + 1 < sql.length() && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    return "";
                }
                i = end + 2;
                continue;
            }
            break;
        }
        return sql.substring(i);
    }

    private static List<String> columnNamesFromSelect(String sql) {
        String trimmed = stripLeadingCommentsAndWhitespace(sql == null ? "" : sql).trim();
        int select = findTopLevelKeyword(trimmed, "select", 0);
        if (select < 0) {
            return List.of();
        }
        int projectionStart = select + "select".length();
        int projectionEnd = firstTopLevelKeyword(trimmed, projectionStart, "from", "where", "group", "having", "order", "limit", "union", "except", "intersect");
        if (projectionEnd < 0) {
            projectionEnd = trimTrailingSemicolon(trimmed).length();
        }
        if (projectionEnd <= projectionStart) {
            return List.of();
        }
        String projection = trimmed.substring(projectionStart, projectionEnd);
        List<String> names = new ArrayList<>();
        for (String expression : splitTopLevelComma(projection)) {
            String name = projectedColumnName(expression);
            if (name.isBlank()) {
                return List.of();
            }
            names.add(name);
        }
        return names;
    }

    private static int firstTopLevelKeyword(String sql, int from, String... keywords) {
        int first = -1;
        for (String keyword : keywords) {
            int index = findTopLevelKeyword(sql, keyword, from);
            if (index >= 0 && (first < 0 || index < first)) {
                first = index;
            }
        }
        return first;
    }

    private static int findTopLevelKeyword(String sql, String keyword, int from) {
        int depth = 0;
        char quote = 0;
        String lowerKeyword = keyword.toLowerCase(java.util.Locale.ROOT);
        for (int i = Math.max(0, from); i <= sql.length() - keyword.length(); i++) {
            char ch = sql.charAt(i);
            if (quote != 0) {
                if ((quote == '[' && ch == ']') || (quote != '[' && ch == quote)) {
                    if (quote == '\'' && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') i++; else quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`' || ch == '[') { quote = ch; continue; }
            if (ch == '(') { depth++; continue; }
            if (ch == ')') { if (depth > 0) depth--; continue; }
            if (depth == 0 && regionMatchesKeyword(sql, i, lowerKeyword)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean regionMatchesKeyword(String sql, int offset, String lowerKeyword) {
        int end = offset + lowerKeyword.length();
        if (end > sql.length()) {
            return false;
        }
        if (offset > 0 && isIdentifierPart(sql.charAt(offset - 1))) {
            return false;
        }
        if (end < sql.length() && isIdentifierPart(sql.charAt(end))) {
            return false;
        }
        return sql.regionMatches(true, offset, lowerKeyword, 0, lowerKeyword.length());
    }

    private static boolean isIdentifierPart(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }

    private static List<String> splitTopLevelComma(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quote != 0) {
                current.append(ch);
                if ((quote == '[' && ch == ']') || (quote != '[' && ch == quote)) {
                    if (quote == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') current.append(text.charAt(++i)); else quote = 0;
                }
            } else if (ch == '\'' || ch == '"' || ch == '`' || ch == '[') {
                quote = ch;
                current.append(ch);
            } else if (ch == '(') {
                depth++;
                current.append(ch);
            } else if (ch == ')') {
                if (depth > 0) depth--;
                current.append(ch);
            } else if (ch == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private static String projectedColumnName(String expression) {
        String trimmed = expression.trim();
        java.util.regex.Matcher alias = java.util.regex.Pattern.compile("(?is).+\\s+as\\s+((\"([^\"]|\"\")+\")|(`([^`]|``)+`)|(\\[[^]]+])|([A-Za-z_][A-Za-z0-9_]*))\\s*$").matcher(trimmed);
        if (alias.matches()) {
            return unquoteIdentifier(alias.group(1));
        }
        java.util.regex.Matcher bare = java.util.regex.Pattern.compile("(?is)(?:[A-Za-z_][A-Za-z0-9_]*\\.)?((\"([^\"]|\"\")+\")|(`([^`]|``)+`)|(\\[[^]]+])|([A-Za-z_][A-Za-z0-9_]*))\\s*$").matcher(trimmed);
        return bare.matches() ? unquoteIdentifier(bare.group(1)) : "";
    }

    private static String unquoteIdentifier(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) return value.substring(1, value.length() - 1).replace("\"\"", "\"");
        if (value.length() >= 2 && value.startsWith("`") && value.endsWith("`")) return value.substring(1, value.length() - 1).replace("``", "`");
        if (value.length() >= 2 && value.startsWith("[") && value.endsWith("]")) return value.substring(1, value.length() - 1);
        return value;
    }

    private static String trimTrailingSemicolon(String sql) {
        String trimmed = sql.trim();
        return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static boolean hasStatementSeparatorBeforeEnd(String sql) {
        boolean lineComment = false;
        boolean blockComment = false;
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (lineComment) {
                if (ch == '\n' || ch == '\r') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (ch == '/' && i > 0 && sql.charAt(i - 1) == '*') blockComment = false;
                continue;
            }
            if (quote != 0) {
                if ((quote == '[' && ch == ']') || (quote != '[' && ch == quote)) {
                    if (quote == '\'' && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') i++; else quote = 0;
                }
                continue;
            }
            if (ch == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') { lineComment = true; i++; continue; }
            if (ch == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') { blockComment = true; i++; continue; }
            if (ch == '\'' || ch == '"' || ch == '`' || ch == '[') { quote = ch; continue; }
            if (ch == ';') {
                return !sql.substring(i + 1).trim().isEmpty();
            }
        }
        return false;
    }

    static List<String> splitSqlStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean lineComment = false;
        boolean blockComment = false;
        char quote = 0;
        StringBuilder token = new StringBuilder();
        List<String> statementTokens = new ArrayList<>();
        boolean inTriggerBody = false;
        int triggerCaseDepth = 0;
        boolean lastEndClosedCase = false;
        String lastToken = "";
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            current.append(ch);
            if (lineComment) {
                if (ch == '\n' || ch == '\r') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (ch == '/' && i > 0 && sql.charAt(i - 1) == '*') blockComment = false;
                continue;
            }
            if (quote != 0) {
                if ((quote == '[' && ch == ']') || (quote != '[' && ch == quote)) {
                    if (quote == '\'' && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        current.append(sql.charAt(++i));
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (ch == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') { lastToken = flushSplitToken(token, statementTokens); inTriggerBody = inTriggerBody || startsCreateTrigger(statementTokens) && "begin".equals(lastToken); lineComment = true; current.append(sql.charAt(++i)); continue; }
            if (ch == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') { lastToken = flushSplitToken(token, statementTokens); inTriggerBody = inTriggerBody || startsCreateTrigger(statementTokens) && "begin".equals(lastToken); blockComment = true; current.append(sql.charAt(++i)); continue; }
            if (ch == '\'' || ch == '"' || ch == '`' || ch == '[') { lastToken = flushSplitToken(token, statementTokens); inTriggerBody = inTriggerBody || startsCreateTrigger(statementTokens) && "begin".equals(lastToken); quote = ch; continue; }
            if (Character.isLetter(ch) || ch == '_') {
                token.append(ch);
                continue;
            }
            boolean hadToken = token.length() > 0;
            lastToken = flushSplitToken(token, statementTokens);
            if (hadToken) {
                if (!inTriggerBody && startsCreateTrigger(statementTokens) && "begin".equals(lastToken)) {
                    inTriggerBody = true;
                    triggerCaseDepth = 0;
                    lastEndClosedCase = false;
                } else if (inTriggerBody && "case".equals(lastToken)) {
                    triggerCaseDepth++;
                    lastEndClosedCase = false;
                } else if (inTriggerBody && "end".equals(lastToken) && triggerCaseDepth > 0) {
                    triggerCaseDepth--;
                    lastEndClosedCase = true;
                } else if (inTriggerBody) {
                    lastEndClosedCase = false;
                }
            }
            if (ch == ';') {
                if (inTriggerBody && (!"end".equals(lastToken) || lastEndClosedCase)) {
                    continue;
                }
                inTriggerBody = false;
                triggerCaseDepth = 0;
                lastEndClosedCase = false;
                addStatement(statements, current);
                current.setLength(0);
                statementTokens.clear();
                lastToken = "";
            }
        }
        flushSplitToken(token, statementTokens);
        addStatement(statements, current);
        return statements;
    }

    private static String flushSplitToken(StringBuilder token, List<String> statementTokens) {
        if (token.length() == 0) {
            return statementTokens.isEmpty() ? "" : statementTokens.get(statementTokens.size() - 1);
        }
        String lower = token.toString().toLowerCase(java.util.Locale.ROOT);
        statementTokens.add(lower);
        token.setLength(0);
        return lower;
    }

    private static boolean startsCreateTrigger(List<String> tokens) {
        if (tokens.isEmpty() || !"create".equals(tokens.get(0))) {
            return false;
        }
        int limit = Math.min(tokens.size(), 8);
        for (int i = 1; i < limit; i++) {
            if ("trigger".equals(tokens.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        if (!statement.isEmpty() && !statement.equals(";")) {
            statements.add(trimTrailingSemicolon(statement));
        }
    }

    static boolean containsExplicitTransactionControl(List<String> statements) {
        for (String statement : statements) {
            if (transactionControl(statement) != TransactionControl.NONE) {
                return true;
            }
        }
        return false;
    }

    static TransactionControl transactionControl(String statement) {
        List<String> tokens = sqlTokens(stripLeadingCommentsAndWhitespace(statement == null ? "" : statement).trim());
        if (tokens.isEmpty()) return TransactionControl.NONE;
        String first = tokens.get(0).toLowerCase(java.util.Locale.ROOT);
        if ("begin".equals(first)) return TransactionControl.BEGIN;
        if ("commit".equals(first) || "end".equals(first)) return TransactionControl.COMMIT;
        if ("savepoint".equals(first)) return TransactionControl.SAVEPOINT;
        if ("release".equals(first)) return TransactionControl.RELEASE;
        if ("rollback".equals(first)) {
            return tokens.size() > 1 && "to".equals(tokens.get(1).toLowerCase(java.util.Locale.ROOT))
                    ? TransactionControl.ROLLBACK_TO
                    : TransactionControl.ROLLBACK;
        }
        return TransactionControl.NONE;
    }

    static String transactionControlName(String statement) {
        TransactionControl control = transactionControl(statement);
        String stripped = stripLeadingCommentsAndWhitespace(statement == null ? "" : statement).trim();
        if (control == TransactionControl.SAVEPOINT || control == TransactionControl.RELEASE) {
            return transactionControlIdentifier(stripped, 1);
        }
        if (control == TransactionControl.ROLLBACK_TO) {
            return transactionControlIdentifier(stripped, 2);
        }
        return null;
    }

    private static String transactionControlIdentifier(String statement, int wordsToSkip) {
        int index = 0;
        for (int skipped = 0; skipped < wordsToSkip; skipped++) {
            index = skipWhitespace(statement, index);
            while (index < statement.length() && Character.isLetter(statement.charAt(index))) {
                index++;
            }
        }
        index = skipWhitespace(statement, index);
        if (index >= statement.length()) {
            return null;
        }
        char quote = statement.charAt(index);
        if (quote == '"' || quote == '`' || quote == '[' || quote == '\'') {
            char endQuote = quote == '[' ? ']' : quote;
            int end = index + 1;
            while (end < statement.length()) {
                char ch = statement.charAt(end++);
                if (ch == endQuote) {
                    if ((quote == '"' || quote == '\'') && end < statement.length() && statement.charAt(end) == endQuote) {
                        end++;
                        continue;
                    }
                    return statement.substring(index, end);
                }
            }
            return null;
        }
        int end = index;
        while (end < statement.length() && !Character.isWhitespace(statement.charAt(end)) && statement.charAt(end) != ';') {
            end++;
        }
        return statement.substring(index, end);
    }

    private static int skipWhitespace(String text, int index) {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    enum TransactionControl {
        NONE,
        BEGIN,
        COMMIT,
        ROLLBACK,
        SAVEPOINT,
        RELEASE,
        ROLLBACK_TO
    }

    static final class ScriptExecutionException extends IOException {
        private final int completedStatements;

        ScriptExecutionException(String message, IOException cause, int completedStatements) {
            super(message, cause);
            this.completedStatements = completedStatements;
        }

        int completedStatements() {
            return completedStatements;
        }
    }

    private static boolean lastStatementReturnsChanges(String sql) {
        List<String> statements = splitSqlStatements(sql);
        if (statements.isEmpty()) return false;
        return statementReturnsChanges(statements.get(statements.size() - 1));
    }

    private static boolean statementReturnsChanges(String statement) {
        List<String> tokens = sqlTokens(stripLeadingCommentsAndWhitespace(statement == null ? "" : statement).trim());
        if (tokens.isEmpty()) return false;
        String first = tokens.get(0).toLowerCase(java.util.Locale.ROOT);
        if (first.equals("insert") || first.equals("update") || first.equals("delete") || first.equals("replace")) {
            return true;
        }
        if (!first.equals("with")) {
            return false;
        }
        for (String token : tokens) {
            String lower = token.toLowerCase(java.util.Locale.ROOT);
            if (lower.equals("insert") || lower.equals("update") || lower.equals("delete") || lower.equals("replace")) {
                return true;
            }
        }
        return false;
    }

    private static String inferredSqliteType(List<List<JsonNode>> rows, int columnIndex) {
        for (List<JsonNode> row : rows) {
            if (columnIndex >= row.size()) {
                continue;
            }
            JsonNode value = row.get(columnIndex);
            if (value == null || value.isNull()) {
                continue;
            }
            if (isEncodedProtocolValue(value) && "blob".equals(value.path("type").asText())) {
                return "BLOB";
            }
            if (value.isIntegralNumber()) {
                return "INTEGER";
            }
            if (value.isFloatingPointNumber()) {
                return "REAL";
            }
            return "TEXT";
        }
        return "";
    }

    private static List<List<String>> parseCsv(String text) throws IOException {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean atFieldStart = true;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (atFieldStart && ch == '"') {
                quoted = true;
                atFieldStart = false;
            } else if (ch == ',') {
                record.add(field.toString());
                field.setLength(0);
                atFieldStart = true;
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                record.add(field.toString());
                field.setLength(0);
                records.add(record);
                record = new ArrayList<>();
                atFieldStart = true;
            } else {
                field.append(ch);
                atFieldStart = false;
            }
        }
        if (quoted) {
            throw new IOException("Unterminated quoted CSV field");
        }
        if (field.length() > 0 || !record.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }

    static String renderSql(String sql, List<Map<String, Object>> params) throws IOException {
        if (params == null || params.isEmpty()) {
            return sql;
        }
        StringBuilder out = new StringBuilder(sql.length() + params.size() * 8);
        int index = 0;
        boolean[] used = new boolean[params.size()];
        Map<String, Integer> namedIndexes = new LinkedHashMap<>();
        boolean lineComment = false;
        boolean blockComment = false;
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (lineComment) {
                out.append(ch);
                if (ch == '\n' || ch == '\r') lineComment = false;
                continue;
            }
            if (blockComment) {
                out.append(ch);
                if (ch == '/' && i > 0 && sql.charAt(i - 1) == '*') blockComment = false;
                continue;
            }
            if (quote != 0) {
                out.append(ch);
                if ((quote == '[' && ch == ']') || (quote != '[' && ch == quote)) {
                    if (quote == '\'' && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        out.append(sql.charAt(++i));
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (ch == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                lineComment = true;
                out.append(ch);
                continue;
            }
            if (ch == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                blockComment = true;
                out.append(ch);
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`' || ch == '[') {
                quote = ch;
                out.append(ch);
                continue;
            }
            if (ch == '?') {
                int paramIndex;
                int j = i + 1;
                while (j < sql.length() && Character.isDigit(sql.charAt(j))) j++;
                if (j == i + 1) {
                    paramIndex = index++;
                } else {
                    paramIndex = Integer.parseInt(sql.substring(i + 1, j)) - 1;
                    index = Math.max(index, paramIndex + 1);
                    i = j - 1;
                }
                if (paramIndex < 0 || paramIndex >= params.size()) {
                    throw new IOException("Missing prepared statement parameter");
                }
                used[paramIndex] = true;
                out.append(sqlLiteral(params.get(paramIndex)));
            } else if ((ch == ':' || ch == '@' || ch == '$') && i + 1 < sql.length() && isNameStart(sql.charAt(i + 1))) {
                int j = i + 2;
                while (j < sql.length() && isNamePart(sql.charAt(j))) j++;
                String name = sql.substring(i, j);
                Integer paramIndex = namedIndexes.get(name);
                if (paramIndex == null) {
                    paramIndex = index++;
                    namedIndexes.put(name, paramIndex);
                }
                if (paramIndex < 0 || paramIndex >= params.size()) {
                    throw new IOException("Missing prepared statement parameter");
                }
                used[paramIndex] = true;
                out.append(sqlLiteral(params.get(paramIndex)));
                i = j - 1;
            } else {
                out.append(ch);
            }
        }
        for (boolean parameterUsed : used) {
            if (!parameterUsed) {
                throw new IOException("Too many prepared statement parameters");
            }
        }
        return out.toString();
    }

    private static boolean isNameStart(char ch) {
        return Character.isLetter(ch) || ch == '_';
    }

    private static boolean isNamePart(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }

    private static String sqlLiteral(Map<String, Object> param) throws IOException {
        String type = String.valueOf(param.get("type"));
        Object value = param.get("value");
        switch (type) {
            case "null": return "NULL";
            case "integer":
            case "real": return String.valueOf(value);
            case "text": return "'" + String.valueOf(value).replace("'", "''") + "'";
            case "blob": return "X'" + hex(Base64.getDecoder().decode(String.valueOf(param.get("base64")))) + "'";
            default: throw new IOException("Unsupported prepared statement parameter type");
        }
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            out[i * 2] = digits[value >>> 4];
            out[i * 2 + 1] = digits[value & 0x0f];
        }
        return new String(out);
    }

    private static final class Cursor {
        final List<String> columns;
        final ArrayDeque<List<JsonNode>> rows;

        Cursor(List<String> columns, ArrayDeque<List<JsonNode>> rows) {
            this.columns = columns;
            this.rows = rows;
        }
    }

    private static final class ParsedCsv {
        final List<String> headers;
        final List<List<JsonNode>> rows;

        ParsedCsv(List<String> headers, List<List<JsonNode>> rows) {
            this.headers = headers;
            this.rows = rows;
        }
    }

    private static final class Field {
        final JsonNode value;
        final int next;

        Field(JsonNode value, int next) {
            this.value = value;
            this.next = next;
        }
    }
}
