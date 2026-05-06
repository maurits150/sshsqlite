package org.sshsqlite.jdbc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class InMemoryResultSet extends BaseResultSet {
    private final BaseResultSetMetaData metaData;
    private final List<List<Object>> rows;
    private final Map<String, Integer> labels = new HashMap<>();
    private boolean closed;
    private int position = -1;
    private boolean lastValueWasNull;

    InMemoryResultSet(BaseStatement statement, List<BaseResultSetMetaData.Column> columns, List<List<Object>> rows) {
        super(statement, new BaseResultSetMetaData(columns));
        this.metaData = new BaseResultSetMetaData(columns);
        this.rows = List.copyOf(rows);
        for (int i = 0; i < columns.size(); i++) {
            labels.putIfAbsent(columns.get(i).label().toLowerCase(Locale.ROOT), i + 1);
            labels.putIfAbsent(columns.get(i).name().toLowerCase(Locale.ROOT), i + 1);
        }
    }

    @Override public boolean next() throws SQLException { checkOpen(); if (position + 1 < rows.size()) { position++; return true; } position = rows.size(); return false; }
    @Override public void close() { closed = true; }
    @Override public boolean isClosed() { return closed; }
    @Override protected void checkOpen() throws SQLException { if (closed) throw JdbcSupport.closedObject(); }
    @Override public ResultSetMetaData getMetaData() throws SQLException { checkOpen(); return metaData; }
    @Override public Statement getStatement() throws SQLException { checkOpen(); return statement; }
    @Override public boolean wasNull() throws SQLException { checkOpen(); return lastValueWasNull; }
    @Override public boolean isBeforeFirst() throws SQLException { checkOpen(); return position < 0 && !rows.isEmpty(); }
    @Override public boolean isAfterLast() throws SQLException { checkOpen(); return position >= rows.size() && !rows.isEmpty(); }
    @Override public boolean isFirst() throws SQLException { checkOpen(); return position == 0 && !rows.isEmpty(); }
    @Override public boolean isLast() throws SQLException { checkOpen(); return position == rows.size() - 1 && !rows.isEmpty(); }

    @Override public int findColumn(String columnLabel) throws SQLException {
        checkOpen();
        Integer index = labels.get(columnLabel.toLowerCase(Locale.ROOT));
        if (index == null) throw JdbcSupport.invalidArgument("Unknown column: " + columnLabel);
        return index;
    }

    @Override public Object getObject(int columnIndex) throws SQLException { return objectValue(columnIndex); }
    @Override public Object getObject(String columnLabel) throws SQLException { return getObject(findColumn(columnLabel)); }
    @Override public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        return ResultSetValueConverter.convert(value(columnIndex), type);
    }
    @Override public <T> T getObject(String columnLabel, Class<T> type) throws SQLException { return getObject(findColumn(columnLabel), type); }
    @Override public String getString(int columnIndex) throws SQLException { Object value = value(columnIndex); return value == null ? null : value.toString(); }
    @Override public String getString(String columnLabel) throws SQLException { return getString(findColumn(columnLabel)); }
    @Override public boolean getBoolean(int columnIndex) throws SQLException { Object value = value(columnIndex); return value != null && ResultSetValueConverter.asBoolean(value); }
    @Override public boolean getBoolean(String columnLabel) throws SQLException { return getBoolean(findColumn(columnLabel)); }
    @Override public byte getByte(int columnIndex) throws SQLException { Object value = value(columnIndex); return value == null ? 0 : ResultSetValueConverter.asByte(value); }
    @Override public byte getByte(String columnLabel) throws SQLException { return getByte(findColumn(columnLabel)); }
    @Override public short getShort(int columnIndex) throws SQLException { Object value = value(columnIndex); return value == null ? 0 : ResultSetValueConverter.asShort(value); }
    @Override public short getShort(String columnLabel) throws SQLException { return getShort(findColumn(columnLabel)); }
    @Override public int getInt(int columnIndex) throws SQLException { Object value = value(columnIndex); return value == null ? 0 : ResultSetValueConverter.asInt(value); }
    @Override public int getInt(String columnLabel) throws SQLException { return getInt(findColumn(columnLabel)); }
    @Override public long getLong(int columnIndex) throws SQLException { Object value = value(columnIndex); return value == null ? 0L : ResultSetValueConverter.asLong(value); }
    @Override public long getLong(String columnLabel) throws SQLException { return getLong(findColumn(columnLabel)); }
    @Override public float getFloat(int columnIndex) throws SQLException { Object value = value(columnIndex); return value == null ? 0.0f : ResultSetValueConverter.asFloat(value); }
    @Override public float getFloat(String columnLabel) throws SQLException { return getFloat(findColumn(columnLabel)); }
    @Override public double getDouble(int columnIndex) throws SQLException { Object value = value(columnIndex); return value == null ? 0.0d : ResultSetValueConverter.asDouble(value); }
    @Override public double getDouble(String columnLabel) throws SQLException { return getDouble(findColumn(columnLabel)); }
    @Override public BigDecimal getBigDecimal(int columnIndex) throws SQLException { Object value = value(columnIndex); return value == null ? null : ResultSetValueConverter.asBigDecimal(value); }
    @Override public BigDecimal getBigDecimal(String columnLabel) throws SQLException { return getBigDecimal(findColumn(columnLabel)); }
    @Override public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException { BigDecimal value = getBigDecimal(columnIndex); return value == null ? null : value.setScale(scale, java.math.RoundingMode.HALF_UP); }
    @Override public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException { return getBigDecimal(findColumn(columnLabel), scale); }
    @Override public byte[] getBytes(int columnIndex) throws SQLException { Object value = value(columnIndex); if (value == null) return null; if (value instanceof byte[]) return ((byte[]) value).clone(); return value.toString().getBytes(StandardCharsets.UTF_8); }
    @Override public byte[] getBytes(String columnLabel) throws SQLException { return getBytes(findColumn(columnLabel)); }
    @Override public Date getDate(int columnIndex) throws SQLException { Object value = value(columnIndex); return value == null ? null : ResultSetValueConverter.parseDate(value); }
    @Override public Date getDate(String columnLabel) throws SQLException { return getDate(findColumn(columnLabel)); }
    @Override public Date getDate(int columnIndex, Calendar cal) throws SQLException { return getDate(columnIndex); }
    @Override public Date getDate(String columnLabel, Calendar cal) throws SQLException { return getDate(columnLabel); }
    @Override public Time getTime(int columnIndex) throws SQLException { Object value = value(columnIndex); return value == null ? null : ResultSetValueConverter.parseTime(value); }
    @Override public Time getTime(String columnLabel) throws SQLException { return getTime(findColumn(columnLabel)); }
    @Override public Time getTime(int columnIndex, Calendar cal) throws SQLException { return getTime(columnIndex); }
    @Override public Time getTime(String columnLabel, Calendar cal) throws SQLException { return getTime(columnLabel); }
    @Override public Timestamp getTimestamp(int columnIndex) throws SQLException { Object value = value(columnIndex); return value == null ? null : ResultSetValueConverter.parseTimestamp(value); }
    @Override public Timestamp getTimestamp(String columnLabel) throws SQLException { return getTimestamp(findColumn(columnLabel)); }
    @Override public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException { return getTimestamp(columnIndex); }
    @Override public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException { return getTimestamp(columnLabel); }

    private Object objectValue(int columnIndex) throws SQLException {
        Object value = value(columnIndex);
        return value instanceof byte[] ? ((byte[]) value).clone() : value;
    }

    private Object value(int columnIndex) throws SQLException {
        checkOpen();
        if (position < 0 || position >= rows.size()) throw JdbcSupport.invalidState("ResultSet is not positioned on a row");
        if (columnIndex < 1 || columnIndex > metaData.getColumnCount()) throw JdbcSupport.invalidArgument("Column index out of range: " + columnIndex);
        Object value = rows.get(position).get(columnIndex - 1);
        lastValueWasNull = value == null;
        return value;
    }
}
