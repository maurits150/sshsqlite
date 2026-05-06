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
    private static final String NULL_SENTINEL = "__SSHSQLITE_NULL__";

    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final ExecutorService executor;
    private final Map<String, Cursor> cursors = new LinkedHashMap<>();
    private final ProtocolClient.HelperMetadata metadata;
    private final BoundedCapture stderr;
    private final Runnable abortBackend;
    private final int maxBufferedResultBytes;
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
        setup(config);
        String version = readVersion(config.queryTimeoutMs);
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
        JsonNode rows = runJson(rendered, timeoutMs);
        List<String> names = columnNames(rows);
        ArrayDeque<List<JsonNode>> decodedRows = new ArrayDeque<>();
        for (JsonNode row : rows) {
            List<JsonNode> values = new ArrayList<>();
            for (String name : names) {
                values.add(encodeJsonValue(row.get(name)));
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
        for (String name : names) {
            ObjectNode column = columns.addObject();
            column.put("name", name);
            column.put("declaredType", "");
            column.put("sqliteType", "");
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
        runNoRows(renderSql(sql, params), timeoutMs);
        JsonNode rows = runJson("SELECT changes() AS changes, last_insert_rowid() AS lastInsertRowid", timeoutMs);
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
        if (isLockContention(e)) {
            return new java.sql.SQLTransientException("SQLite lock contention: " + e.getMessage(), "HYT00", e);
        }
        if (broken) {
            return JdbcSupport.brokenConnection("SSHSQLite sqlite3 CLI connection is broken", e);
        }
        return new SQLException(e.getMessage(), JdbcSupport.GENERAL_SQL_STATE, e);
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
        writeLine(".bail on");
        writeLine(".headers on");
        writeLine(".mode csv");
        writeLine(".nullvalue " + NULL_SENTINEL);
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
            String stderrText = stderr == null ? "" : stderr.text();
            throw statementError("Unable to parse sqlite3 output. stdout=" + diagnosticSnippet(output)
                    + (stderrText.isBlank() ? "" : " stderr=" + diagnosticSnippet(stderrText)), e);
        }
    }

    private void runNoRows(String sql, int timeoutMs) throws IOException {
        String output = run(sql, timeoutMs);
        if (!output.isBlank()) {
            throw statementError("sqlite3 update produced unexpected result output");
        }
    }

    private String run(String sql, int timeoutMs) throws IOException {
        ensureSafeSql(sql);
        String marker = "__SSHSQLITE_DONE_" + UUID.randomUUID().toString().replace('-', '_') + "__";
        Future<String> future = executor.submit(readUntil(marker));
        writeSql(sql);
        writeLine(".print " + marker);
        try {
            String output = timeoutMs <= 0 ? future.get() : future.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (stderr != null && !stderr.text().isBlank() && output.isBlank()) {
                throw statementError(stderr.text());
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
            markBrokenAndAbort();
            throw new IOException("sqlite3 CLI exited while waiting for response");
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
        writer.write(line);
        writer.newLine();
        writer.flush();
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
        String trimmed = sql.trim();
        if (trimmed.startsWith(".")) {
            throw new IOException("SQLite dot commands are not allowed");
        }
        if (containsMultipleStatements(sql)) {
            throw new IOException("Multiple SQL statements are not supported");
        }
    }

    private static boolean containsMultipleStatements(String sql) {
        boolean sawTerminator = false;
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
                if (ch == quote) {
                    if (quote == '\'' && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (ch == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                lineComment = true;
                i++;
                continue;
            }
            if (ch == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                blockComment = true;
                i++;
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                continue;
            }
            if (ch == ';') {
                sawTerminator = true;
                continue;
            }
            if (sawTerminator && !Character.isWhitespace(ch)) {
                return true;
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

    private static List<String> columnNames(JsonNode rows) {
        List<String> names = new ArrayList<>();
        if (rows.isArray() && rows.size() > 0 && rows.get(0).isObject()) {
            rows.get(0).fieldNames().forEachRemaining(names::add);
        }
        return names;
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

    private static JsonNode parseCsvRows(String output) throws IOException {
        ArrayNode rows = JsonNodeFactory.instance.arrayNode();
        if (output == null || output.isBlank()) {
            return rows;
        }
        List<List<String>> records = parseCsv(output);
        if (records.isEmpty()) {
            return rows;
        }
        List<String> headers = records.get(0);
        for (int i = 1; i < records.size(); i++) {
            List<String> record = records.get(i);
            if (record.size() != headers.size()) {
                throw new IOException("sqlite3 CSV row had " + record.size() + " columns, expected " + headers.size());
            }
            ObjectNode row = rows.addObject();
            for (int column = 0; column < headers.size(); column++) {
                putCsvValue(row, headers.get(column), record.get(column));
            }
        }
        return rows;
    }

    private static void putCsvValue(ObjectNode row, String name, String value) {
        if (NULL_SENTINEL.equals(value)) {
            row.putNull(name);
        } else if (value.matches("-?(0|[1-9][0-9]*)")) {
            try {
                row.put(name, Long.parseLong(value));
                return;
            } catch (NumberFormatException ignored) {
                row.put(name, value);
            }
        } else if (value.matches("-?((([0-9]+)\\.([0-9]*))|(([0-9]*)\\.([0-9]+)))([eE][+-]?[0-9]+)?|-?[0-9]+[eE][+-]?[0-9]+")) {
            try {
                row.put(name, Double.parseDouble(value));
                return;
            } catch (NumberFormatException ignored) {
                row.put(name, value);
            }
        } else {
            row.put(name, value);
        }
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
                if (ch == quote) {
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
            if (ch == '\'' || ch == '"' || ch == '`') {
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
}
