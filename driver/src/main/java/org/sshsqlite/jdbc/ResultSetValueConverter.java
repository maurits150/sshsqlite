package org.sshsqlite.jdbc;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

final class ResultSetValueConverter {
    private ResultSetValueConverter() {
    }

    static <T> T convert(Object value, Class<T> type) throws SQLException {
        if (type == null) {
            throw JdbcSupport.invalidArgument("target type must not be null");
        }
        if (value == null) {
            return null;
        }
        if (type == Object.class) {
            if (value instanceof byte[]) {
                return type.cast(((byte[]) value).clone());
            }
            return type.cast(value);
        }
        if (type.isInstance(value)) {
            if (value instanceof byte[]) {
                return type.cast(((byte[]) value).clone());
            }
            return type.cast(value);
        }
        if (type == String.class) {
            return type.cast(value.toString());
        }
        if (type == Integer.class) {
            return type.cast(Integer.valueOf(asInt(value)));
        }
        if (type == Short.class) {
            return type.cast(Short.valueOf(asShort(value)));
        }
        if (type == Byte.class) {
            return type.cast(Byte.valueOf(asByte(value)));
        }
        if (type == Long.class) {
            return type.cast(Long.valueOf(asLong(value)));
        }
        if (type == Double.class) {
            return type.cast(Double.valueOf(asDouble(value)));
        }
        if (type == Float.class) {
            return type.cast(Float.valueOf(asFloat(value)));
        }
        if (type == Boolean.class) {
            return type.cast(Boolean.valueOf(asBoolean(value)));
        }
        if (type == byte[].class) {
            if (value instanceof byte[]) {
                return type.cast(((byte[]) value).clone());
            }
            return type.cast(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (type == BigDecimal.class) {
            return type.cast(asBigDecimal(value));
        }
        if (type == java.sql.Date.class) {
            return type.cast(parseDate(value));
        }
        if (type == Time.class) {
            return type.cast(parseTime(value));
        }
        if (type == Timestamp.class) {
            return type.cast(parseTimestamp(value));
        }
        if (type == LocalDate.class) {
            return type.cast(parseLocalDate(value));
        }
        if (type == LocalTime.class) {
            return type.cast(parseLocalTime(value));
        }
        if (type == LocalDateTime.class) {
            return type.cast(parseLocalDateTime(value));
        }
        throw JdbcSupport.unsupported();
    }

    static BigDecimal asBigDecimal(Object value) throws SQLException {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return new BigDecimal(value.toString());
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            throw new SQLException("Cannot convert value to BigDecimal", JdbcSupport.GENERAL_SQL_STATE, e);
        }
    }

    static long asLong(Object value) throws SQLException {
        try {
            return asBigDecimal(value).longValueExact();
        } catch (ArithmeticException e) {
            throw new SQLException("Cannot convert value to Long without overflow or rounding", JdbcSupport.GENERAL_SQL_STATE, e);
        }
    }

    static int asInt(Object value) throws SQLException {
        long converted = asLong(value);
        if (converted < Integer.MIN_VALUE || converted > Integer.MAX_VALUE) {
            throw new SQLException("Cannot convert value to Integer without overflow", JdbcSupport.GENERAL_SQL_STATE);
        }
        return (int) converted;
    }

    static short asShort(Object value) throws SQLException {
        long converted = asLong(value);
        if (converted < Short.MIN_VALUE || converted > Short.MAX_VALUE) {
            throw new SQLException("Cannot convert value to Short without overflow", JdbcSupport.GENERAL_SQL_STATE);
        }
        return (short) converted;
    }

    static byte asByte(Object value) throws SQLException {
        long converted = asLong(value);
        if (converted < Byte.MIN_VALUE || converted > Byte.MAX_VALUE) {
            throw new SQLException("Cannot convert value to Byte without overflow", JdbcSupport.GENERAL_SQL_STATE);
        }
        return (byte) converted;
    }

    static double asDouble(Object value) throws SQLException {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            throw new SQLException("Cannot convert value to Double", JdbcSupport.GENERAL_SQL_STATE, e);
        }
    }

    static float asFloat(Object value) throws SQLException {
        double converted = asDouble(value);
        if (Double.isFinite(converted) && (converted < -Float.MAX_VALUE || converted > Float.MAX_VALUE)) {
            throw new SQLException("Cannot convert value to Float without overflow", JdbcSupport.GENERAL_SQL_STATE);
        }
        return (float) converted;
    }

    static boolean asBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0.0d;
        }
        String text = value.toString().trim();
        return "1".equals(text) || Boolean.parseBoolean(text);
    }

    static java.sql.Date parseDate(Object value) throws SQLException {
        try {
            return java.sql.Date.valueOf(value.toString());
        } catch (IllegalArgumentException e) {
            throw new SQLException("Cannot convert value to Date", JdbcSupport.GENERAL_SQL_STATE, e);
        }
    }

    static Time parseTime(Object value) throws SQLException {
        try {
            return Time.valueOf(value.toString());
        } catch (IllegalArgumentException e) {
            throw new SQLException("Cannot convert value to Time", JdbcSupport.GENERAL_SQL_STATE, e);
        }
    }

    static Timestamp parseTimestamp(Object value) throws SQLException {
        try {
            return Timestamp.valueOf(value.toString());
        } catch (IllegalArgumentException e) {
            throw new SQLException("Cannot convert value to Timestamp", JdbcSupport.GENERAL_SQL_STATE, e);
        }
    }

    private static LocalDate parseLocalDate(Object value) throws SQLException {
        try {
            return LocalDate.parse(value.toString());
        } catch (DateTimeParseException e) {
            throw new SQLException("Cannot convert value to LocalDate", JdbcSupport.GENERAL_SQL_STATE, e);
        }
    }

    private static LocalTime parseLocalTime(Object value) throws SQLException {
        try {
            return LocalTime.parse(value.toString());
        } catch (DateTimeParseException e) {
            throw new SQLException("Cannot convert value to LocalTime", JdbcSupport.GENERAL_SQL_STATE, e);
        }
    }

    private static LocalDateTime parseLocalDateTime(Object value) throws SQLException {
        try {
            return LocalDateTime.parse(value.toString().replace(' ', 'T'));
        } catch (DateTimeParseException e) {
            throw new SQLException("Cannot convert value to LocalDateTime", JdbcSupport.GENERAL_SQL_STATE, e);
        }
    }
}
