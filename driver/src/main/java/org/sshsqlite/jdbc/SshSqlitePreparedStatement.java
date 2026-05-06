package org.sshsqlite.jdbc;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

public final class SshSqlitePreparedStatement extends BasePreparedStatement {
    private final SshSqliteConnection connection;
    private final int parameterCount;
    private final boolean returnGeneratedKeys;
    private final Map<Integer, Map<String, Object>> parameters = new HashMap<>();
    private ResultSet currentResultSet;
    private ResultSet generatedKeys;
    private long updateCount = -1;

    public SshSqlitePreparedStatement(SshSqliteConnection connection, String sql) throws SQLException {
        super(connection, sql);
        this.connection = connection;
        this.returnGeneratedKeys = false;
        this.parameterCount = countParameters(sql);
    }

    public SshSqlitePreparedStatement(SshSqliteConnection connection, String sql, boolean returnGeneratedKeys) throws SQLException {
        super(connection, sql);
        this.connection = connection;
        this.returnGeneratedKeys = returnGeneratedKeys;
        this.parameterCount = countParameters(sql);
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        checkOpen();
        clearExecutionState(true);
        currentResultSet = startQuery();
        return currentResultSet;
    }

    @Override
    public boolean execute() throws SQLException {
        checkOpen();
        clearExecutionState(true);
        clearGeneratedKeys();
        if (rowProducingStatementCount() > 0) {
            currentResultSet = startQuery();
            return true;
        }
        updateCount = executeUpdateInternal();
        return false;
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        checkOpen();
        return currentResultSet;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        checkOpen();
        return updateCount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) updateCount;
    }

    @Override
    public long getLargeUpdateCount() throws SQLException {
        checkOpen();
        return updateCount;
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        checkOpen();
        closeCurrentResultSet();
        updateCount = -1;
        return false;
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        checkOpen();
        if (current != Statement.KEEP_CURRENT_RESULT) {
            closeCurrentResultSet();
        }
        updateCount = -1;
        return false;
    }

    @Override
    public void close() throws SQLException {
        closeCurrentResultSet();
        clearGeneratedKeys();
        super.close();
    }

    @Override
    public void clearParameters() throws SQLException {
        checkOpen();
        parameters.clear();
    }

    @Override public void setNull(int parameterIndex, int sqlType) throws SQLException { bind(parameterIndex, value("null", null)); }
    @Override public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException { setNull(parameterIndex, sqlType); }
    @Override public void setString(int parameterIndex, String x) throws SQLException { bindNullable(parameterIndex, x == null ? null : value("text", x)); }
    @Override public void setInt(int parameterIndex, int x) throws SQLException { bind(parameterIndex, value("integer", x)); }
    @Override public void setLong(int parameterIndex, long x) throws SQLException { bind(parameterIndex, value("integer", x)); }
    @Override public void setDouble(int parameterIndex, double x) throws SQLException { bind(parameterIndex, value("real", finite(x))); }
    @Override public void setFloat(int parameterIndex, float x) throws SQLException { bind(parameterIndex, value("real", finite(x))); }
    @Override public void setBoolean(int parameterIndex, boolean x) throws SQLException { bind(parameterIndex, value("integer", x ? 1 : 0)); }
    @Override public void setBytes(int parameterIndex, byte[] x) throws SQLException { bindNullable(parameterIndex, x == null ? null : blob(x)); }
    @Override public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException { bindNullable(parameterIndex, x == null ? null : value("text", x.toPlainString())); }
    @Override public void setDate(int parameterIndex, java.sql.Date x) throws SQLException { bindNullable(parameterIndex, x == null ? null : value("text", x.toString())); }
    @Override public void setTime(int parameterIndex, Time x) throws SQLException { bindNullable(parameterIndex, x == null ? null : value("text", x.toString())); }
    @Override public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException { bindNullable(parameterIndex, x == null ? null : value("text", x.toString())); }
    @Override public void setDate(int parameterIndex, java.sql.Date x, Calendar cal) throws SQLException { setDate(parameterIndex, x); }
    @Override public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException { setTime(parameterIndex, x); }
    @Override public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException { setTimestamp(parameterIndex, x); }

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        if (x == null) setNull(parameterIndex, Types.NULL);
        else if (x instanceof String) setString(parameterIndex, (String) x);
        else if (x instanceof Integer) setInt(parameterIndex, (Integer) x);
        else if (x instanceof Long) setLong(parameterIndex, (Long) x);
        else if (x instanceof Short) setInt(parameterIndex, (Short) x);
        else if (x instanceof Byte) setInt(parameterIndex, (Byte) x);
        else if (x instanceof Double) setDouble(parameterIndex, (Double) x);
        else if (x instanceof Float) setFloat(parameterIndex, (Float) x);
        else if (x instanceof Boolean) setBoolean(parameterIndex, (Boolean) x);
        else if (x instanceof byte[]) setBytes(parameterIndex, (byte[]) x);
        else if (x instanceof BigDecimal) setBigDecimal(parameterIndex, (BigDecimal) x);
        else if (x instanceof java.sql.Date) setDate(parameterIndex, (java.sql.Date) x);
        else if (x instanceof Time) setTime(parameterIndex, (Time) x);
        else if (x instanceof Timestamp) setTimestamp(parameterIndex, (Timestamp) x);
        else throw JdbcSupport.unsupported();
    }

    @Override public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException { setObject(parameterIndex, x); }
    @Override public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException { setObject(parameterIndex, x); }
    @Override public void setObject(int parameterIndex, Object x, SQLType targetSqlType) throws SQLException { setObject(parameterIndex, x); }
    @Override public void setObject(int parameterIndex, Object x, SQLType targetSqlType, int scaleOrLength) throws SQLException { setObject(parameterIndex, x); }

    @Override public int executeUpdate() throws SQLException {
        long count = executeLargeUpdate();
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }
    @Override public long executeLargeUpdate() throws SQLException {
        checkOpen();
        clearExecutionState(true);
        clearGeneratedKeys();
        updateCount = executeUpdateInternal();
        return updateCount;
    }
    @Override public ResultSet getGeneratedKeys() throws SQLException { checkOpen(); if (generatedKeys == null) generatedKeys = SshSqliteStatement.generatedKeysResultSet(null); return generatedKeys; }
    @Override public ResultSetMetaData getMetaData() throws SQLException { checkOpen(); return null; }
    @Override public ParameterMetaData getParameterMetaData() throws SQLException { checkOpen(); throw JdbcSupport.unsupported(); }

    @Override public ResultSet executeQuery(String sql) throws SQLException { throw fixedSqlError(); }
    @Override public boolean execute(String sql) throws SQLException { throw fixedSqlError(); }
    @Override public boolean execute(String sql, int autoGeneratedKeys) throws SQLException { throw fixedSqlError(); }
    @Override public boolean execute(String sql, int[] columnIndexes) throws SQLException { throw fixedSqlError(); }
    @Override public boolean execute(String sql, String[] columnNames) throws SQLException { throw fixedSqlError(); }
    @Override public int executeUpdate(String sql) throws SQLException { throw fixedSqlError(); }
    @Override public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException { throw fixedSqlError(); }
    @Override public int executeUpdate(String sql, int[] columnIndexes) throws SQLException { throw fixedSqlError(); }
    @Override public int executeUpdate(String sql, String[] columnNames) throws SQLException { throw fixedSqlError(); }
    @Override public long executeLargeUpdate(String sql) throws SQLException { throw fixedSqlError(); }
    @Override public long executeLargeUpdate(String sql, int autoGeneratedKeys) throws SQLException { throw fixedSqlError(); }
    @Override public long executeLargeUpdate(String sql, int[] columnIndexes) throws SQLException { throw fixedSqlError(); }
    @Override public long executeLargeUpdate(String sql, String[] columnNames) throws SQLException { throw fixedSqlError(); }
    @Override public void addBatch(String sql) throws SQLException { throw fixedSqlError(); }

    private ResultSet startQuery() throws SQLException {
        int rowProducingStatements = rowProducingStatementCount();
        if (rowProducingStatements == 0) {
            throw JdbcSupport.invalidArgument("executeQuery requires a statement that returns rows");
        }
        if (rowProducingStatements > 1) {
            throw JdbcSupport.invalidArgument("Multiple row-producing SQL statements are not supported by this JDBC driver");
        }
        validateCompleteParameters();
        closeCurrentResultSet();
        try {
            connection.ensureTransactionForStatement(sql);
            JsonNode response = connection.protocol().query(sql, orderedParameters(), getMaxRows(), effectiveFetchSize(), queryTimeoutMs());
            connection.syncExplicitTransactionState(sql);
            return new SshSqliteResultSet(this, response.path("cursorId").asText(), response.path("columns"));
        } catch (Exception e) {
            connection.recoverFailedExplicitTransactionScript(sql, e);
            throw connection.sqlException(e);
        }
    }

    private long executeUpdateInternal() throws SQLException {
        connection.ensureWritesAllowed();
        if (rowProducingStatementCount() > 0) {
            throw JdbcSupport.invalidArgument("executeUpdate requires a statement that does not return rows");
        }
        validateCompleteParameters();
        try {
            connection.ensureTransactionForStatement(sql);
            JsonNode response = connection.protocol().exec(sql, orderedParameters(), queryTimeoutMs());
            connection.syncExplicitTransactionState(sql);
            if (returnGeneratedKeys) {
                generatedKeys = SshSqliteStatement.generatedKeysResultSet(response.path("lastInsertRowid").asLong());
            }
            return response.path("changes").asLong();
        } catch (Exception e) {
            connection.recoverFailedExplicitTransactionScript(sql, e);
            throw connection.sqlException(e);
        }
    }

    private void clearExecutionState(boolean closeResultSet) throws SQLException {
        if (closeResultSet) {
            closeCurrentResultSet();
        } else {
            currentResultSet = null;
        }
        updateCount = -1;
    }

    int effectiveFetchSize() throws SQLException {
        int fetchSize = getFetchSize();
        return fetchSize > 0 ? fetchSize : connection.defaultFetchSize();
    }

    int queryTimeoutMs() throws SQLException {
        int seconds = getQueryTimeout();
        return seconds > 0 ? seconds * 1000 : connection.defaultQueryTimeoutMs();
    }

    SqlClient protocol() throws SQLException {
        return connection.protocol();
    }

    private int rowProducingStatementCount() {
        int count = 0;
        for (String statement : CliProtocolClient.splitSqlStatements(sql)) {
            if (SshSqliteStatement.returnsRows(statement)) {
                count++;
            }
        }
        return count;
    }

    private void closeCurrentResultSet() throws SQLException {
        if (currentResultSet != null) {
            currentResultSet.close();
            currentResultSet = null;
        }
    }

    private void clearGeneratedKeys() throws SQLException {
        if (generatedKeys != null) {
            generatedKeys.close();
            generatedKeys = null;
        }
    }

    private void bindNullable(int index, Map<String, Object> encoded) throws SQLException {
        bind(index, encoded == null ? value("null", null) : encoded);
    }

    private void bind(int index, Map<String, Object> encoded) throws SQLException {
        checkOpen();
        if (index < 1 || (parameterCount >= 0 && index > parameterCount)) {
            throw JdbcSupport.invalidArgument("invalid parameter index: " + index);
        }
        parameters.put(index, encoded);
    }

    private void validateCompleteParameters() throws SQLException {
        for (int i = 1; i <= parameterCount; i++) {
            if (!parameters.containsKey(i)) {
                throw JdbcSupport.invalidArgument("missing parameter: " + i);
            }
        }
        for (int index : parameters.keySet()) {
            if (index > parameterCount) {
                throw JdbcSupport.invalidArgument("invalid parameter index: " + index);
            }
        }
    }

    private List<Map<String, Object>> orderedParameters() {
        List<Map<String, Object>> out = new ArrayList<>(parameterCount);
        for (int i = 1; i <= parameterCount; i++) {
            out.add(parameters.get(i));
        }
        return out;
    }

    private static Map<String, Object> value(String type, Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        if (value != null) out.put("value", value);
        return out;
    }

    private static Map<String, Object> blob(byte[] value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "blob");
        out.put("base64", Base64.getEncoder().encodeToString(value));
        return out;
    }

    private static double finite(double value) throws SQLException {
        if (!Double.isFinite(value)) {
            throw JdbcSupport.invalidArgument("real parameter must be finite");
        }
        return value;
    }

    private SQLException fixedSqlError() throws SQLException {
        checkOpen();
        return JdbcSupport.invalidArgument("PreparedStatement SQL is fixed; use parameterless execution methods");
    }

    private static int countParameters(String sql) throws SQLException {
        int count = 0;
        int plain = 0;
        Set<Integer> numbered = new HashSet<>();
        Set<String> named = new HashSet<>();
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
                lineComment = true;
                continue;
            }
            if (ch == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                blockComment = true;
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`' || ch == '[') {
                quote = ch;
                continue;
            }
            if (ch == '?') {
                int j = i + 1;
                while (j < sql.length() && Character.isDigit(sql.charAt(j))) j++;
                if (j == i + 1) {
                    count++;
                    plain++;
                } else {
                    int n = Integer.parseInt(sql.substring(i + 1, j));
                    if (n <= 0) throw JdbcSupport.invalidArgument("invalid ?NNN parameter index");
                    numbered.add(n);
                    count = Math.max(count, n);
                    i = j - 1;
                }
                continue;
            }
            if ((ch == ':' || ch == '@' || ch == '$') && i + 1 < sql.length() && isNameStart(sql.charAt(i + 1))) {
                int j = i + 2;
                while (j < sql.length() && isNamePart(sql.charAt(j))) j++;
                String name = sql.substring(i, j);
                if (named.add(name)) count++;
                i = j - 1;
            }
        }
        if (!numbered.isEmpty()) {
            int max = numbered.stream().mapToInt(Integer::intValue).max().orElse(0);
            if (max > numbered.size() + plain) {
                throw JdbcSupport.invalidArgument("sparse ?NNN parameters are not supported");
            }
        }
        return count;
    }

    private static boolean isNameStart(char ch) {
        return Character.isLetter(ch) || ch == '_';
    }

    private static boolean isNamePart(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }
}
