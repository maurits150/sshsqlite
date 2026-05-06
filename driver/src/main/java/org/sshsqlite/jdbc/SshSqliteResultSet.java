package org.sshsqlite.jdbc;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SshSqliteResultSet extends BaseResultSet {
    private final BaseStatement statement;
    private final String cursorId;
    private final SshSqliteResultSetMetaData sshMetaData;
    private final int columnCount;
    private final Map<String, Integer> labels = new HashMap<>();
    private final ArrayDeque<List<Object>> batch = new ArrayDeque<>();
    private boolean closed;
    private boolean beforeFirst = true;
    private boolean afterLast;
    private boolean remoteDone;
    private boolean lastValueWasNull;
    private boolean hasCurrentRow;
    private List<Object> currentRow;

    public SshSqliteResultSet(BaseStatement statement, BaseResultSetMetaData metaData) {
        super(statement, metaData);
        this.statement = null;
        this.cursorId = null;
        this.sshMetaData = metaData instanceof SshSqliteResultSetMetaData ? (SshSqliteResultSetMetaData) metaData : new SshSqliteResultSetMetaData();
        this.columnCount = 0;
    }

    SshSqliteResultSet(BaseStatement statement, String cursorId, JsonNode columns) throws SQLException {
        super(statement, metadata(columns));
        this.statement = statement;
        this.cursorId = cursorId;
        this.sshMetaData = metadata(columns);
        this.columnCount = sshMetaData.getColumnCount();
        for (int i = 1; i <= columnCount; i++) {
            labels.putIfAbsent(sshMetaData.getColumnLabel(i).toLowerCase(Locale.ROOT), i);
            labels.putIfAbsent(sshMetaData.getColumnName(i).toLowerCase(Locale.ROOT), i);
        }
    }

    private static SshSqliteResultSetMetaData metadata(JsonNode columns) {
        List<BaseResultSetMetaData.Column> result = new ArrayList<>();
        if (columns != null && columns.isArray()) {
            for (JsonNode column : columns) {
                String name = column.path("name").asText("");
                String declaredType = column.path("declaredType").asText("");
                String sqliteType = column.path("sqliteType").asText("");
                String typeName = !declaredType.isBlank() ? declaredType : (!sqliteType.isBlank() ? sqliteType.toUpperCase(Locale.ROOT) : "");
                int sqlType = sqlType(typeName, sqliteType);
                result.add(new BaseResultSetMetaData.Column(name, name, sqlType, typeName, className(sqlType), column.path("nullable").asBoolean(true)));
            }
        }
        return new SshSqliteResultSetMetaData(result);
    }

    private static int sqlType(String typeName, String sqliteType) {
        String normalized = (typeName + " " + sqliteType).toUpperCase(Locale.ROOT);
        if (normalized.contains("INT")) return Types.BIGINT;
        if (normalized.contains("REAL") || normalized.contains("FLOA") || normalized.contains("DOUB")) return Types.DOUBLE;
        if (normalized.contains("BLOB")) return Types.BLOB;
        if (normalized.contains("CHAR") || normalized.contains("CLOB") || normalized.contains("TEXT")) return Types.VARCHAR;
        if (normalized.contains("NUM") || normalized.contains("DEC")) return Types.NUMERIC;
        return Types.VARCHAR;
    }

    private static String className(int sqlType) {
        switch (sqlType) {
            case Types.BIGINT:
            case Types.INTEGER:
                return Long.class.getName();
            case Types.DOUBLE:
            case Types.REAL:
            case Types.FLOAT:
                return Double.class.getName();
            case Types.BLOB:
            case Types.BINARY:
            case Types.VARBINARY:
                return byte[].class.getName();
            default:
                return String.class.getName();
        }
    }

    @Override public boolean next() throws SQLException {
        checkOpen();
        if (batch.isEmpty() && !remoteDone) fetchBatch();
        currentRow = batch.pollFirst();
        hasCurrentRow = currentRow != null;
        beforeFirst = false;
        if (!hasCurrentRow) afterLast = true;
        return hasCurrentRow;
    }

    @Override public void close() throws SQLException {
        if (closed) return;
        closed = true;
        if (!remoteDone && statement != null) {
            try {
                protocol().closeCursor(cursorId);
                remoteDone = true;
            } catch (Exception e) {
                SshSqliteConnection connection = (SshSqliteConnection) statement.getConnection();
                connection.markBroken();
                throw connection.sqlException(e);
            }
        }
    }

    @Override public boolean isClosed() { return closed; }
    @Override protected void checkOpen() throws SQLException { if (closed) throw JdbcSupport.closedObject(); }
    @Override public ResultSetMetaData getMetaData() throws SQLException { checkOpen(); return sshMetaData; }
    @Override public boolean wasNull() throws SQLException { checkOpen(); return lastValueWasNull; }
    @Override public Statement getStatement() throws SQLException { checkOpen(); return statement; }
    @Override public boolean isBeforeFirst() throws SQLException { checkOpen(); return beforeFirst; }
    @Override public boolean isAfterLast() throws SQLException { checkOpen(); return afterLast; }
    @Override public boolean isFirst() throws SQLException { checkOpen(); return !beforeFirst && hasCurrentRow; }
    @Override public boolean isLast() throws SQLException { checkOpen(); return remoteDone && batch.isEmpty() && hasCurrentRow; }
    @Override public int findColumn(String label) throws SQLException {
        checkOpen();
        Integer index = labels.get(label.toLowerCase(Locale.ROOT));
        if (index == null) throw JdbcSupport.invalidArgument("Unknown column: " + label);
        return index;
    }

    @Override public Object getObject(int columnIndex) throws SQLException { return value(columnIndex); }
    @Override public Object getObject(String columnLabel) throws SQLException { return getObject(findColumn(columnLabel)); }
    @Override public String getString(int columnIndex) throws SQLException { Object value = value(columnIndex); return value == null ? null : value instanceof byte[] ? Base64.getEncoder().encodeToString((byte[]) value) : value.toString(); }
    @Override public String getString(String columnLabel) throws SQLException { return getString(findColumn(columnLabel)); }
    @Override public long getLong(int columnIndex) throws SQLException { Object value = value(columnIndex); if (value == null) return 0L; if (value instanceof Number) return ((Number) value).longValue(); return Long.parseLong(value.toString()); }
    @Override public long getLong(String columnLabel) throws SQLException { return getLong(findColumn(columnLabel)); }
    @Override public int getInt(int columnIndex) throws SQLException { return (int) getLong(columnIndex); }
    @Override public int getInt(String columnLabel) throws SQLException { return getInt(findColumn(columnLabel)); }
    @Override public double getDouble(int columnIndex) throws SQLException { Object value = value(columnIndex); if (value == null) return 0.0d; if (value instanceof Number) return ((Number) value).doubleValue(); return Double.parseDouble(value.toString()); }
    @Override public double getDouble(String columnLabel) throws SQLException { return getDouble(findColumn(columnLabel)); }
    @Override public byte[] getBytes(int columnIndex) throws SQLException { Object value = value(columnIndex); if (value == null) return null; if (value instanceof byte[]) return ((byte[]) value).clone(); return value.toString().getBytes(StandardCharsets.UTF_8); }
    @Override public byte[] getBytes(String columnLabel) throws SQLException { return getBytes(findColumn(columnLabel)); }
    @Override public boolean getBoolean(int columnIndex) throws SQLException { Object value = value(columnIndex); if (value == null) return false; if (value instanceof Number) return ((Number) value).doubleValue() != 0.0d; return Boolean.parseBoolean(value.toString()); }
    @Override public boolean getBoolean(String columnLabel) throws SQLException { return getBoolean(findColumn(columnLabel)); }

    private Object value(int columnIndex) throws SQLException {
        checkOpen();
        if (!hasCurrentRow) throw JdbcSupport.invalidState("ResultSet is not positioned on a row");
        if (columnIndex < 1 || columnIndex > columnCount) throw JdbcSupport.invalidArgument("Column index out of range: " + columnIndex);
        Object value = currentRow.get(columnIndex - 1);
        lastValueWasNull = value == null;
        return value;
    }

    private void fetchBatch() throws SQLException {
        try {
            JsonNode response = protocol().fetch(cursorId, effectiveFetchSize(), queryTimeoutMs());
            remoteDone = response.path("done").asBoolean(false);
            for (JsonNode row : response.path("rows")) {
                List<Object> values = new ArrayList<>();
                for (JsonNode value : row) values.add(decodeValue(value));
                batch.add(values);
            }
        } catch (Exception e) {
            remoteDone = true;
            throw ((SshSqliteConnection) statement.getConnection()).sqlException(e);
        }
    }

    private SqlClient protocol() throws SQLException {
        if (statement instanceof SshSqliteStatement) return ((SshSqliteStatement) statement).protocol();
        if (statement instanceof SshSqlitePreparedStatement) return ((SshSqlitePreparedStatement) statement).protocol();
        throw JdbcSupport.invalidState("ResultSet statement does not support remote protocol");
    }

    private int effectiveFetchSize() throws SQLException {
        if (statement instanceof SshSqliteStatement) return ((SshSqliteStatement) statement).effectiveFetchSize();
        if (statement instanceof SshSqlitePreparedStatement) return ((SshSqlitePreparedStatement) statement).effectiveFetchSize();
        throw JdbcSupport.invalidState("ResultSet statement does not support fetch size");
    }

    private int queryTimeoutMs() throws SQLException {
        if (statement instanceof SshSqliteStatement) return ((SshSqliteStatement) statement).queryTimeoutMs();
        if (statement instanceof SshSqlitePreparedStatement) return ((SshSqlitePreparedStatement) statement).queryTimeoutMs();
        throw JdbcSupport.invalidState("ResultSet statement does not support query timeout");
    }

    private static Object decodeValue(JsonNode value) throws SQLException {
        String type = value.path("type").asText();
        switch (type) {
            case "null": return null;
            case "integer": return value.path("value").asLong();
            case "real": return value.path("value").asDouble();
            case "text": return value.path("value").asText();
            case "blob": return Base64.getDecoder().decode(value.path("base64").asText());
            default: throw new SQLException("Unknown protocol value type: " + type, JdbcSupport.GENERAL_SQL_STATE);
        }
    }
}
