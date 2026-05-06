package org.sshsqlite.jdbc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcInterfaceContractTest {
    private static final List<InterfaceImplementation> IMPLEMENTATIONS = List.of(
            new InterfaceImplementation(Driver.class, BaseDriver.class),
            new InterfaceImplementation(Driver.class, SshSqliteDriver.class),
            new InterfaceImplementation(Connection.class, BaseConnection.class),
            new InterfaceImplementation(Connection.class, SshSqliteConnection.class),
            new InterfaceImplementation(Statement.class, BaseStatement.class),
            new InterfaceImplementation(Statement.class, SshSqliteStatement.class),
            new InterfaceImplementation(PreparedStatement.class, BasePreparedStatement.class),
            new InterfaceImplementation(PreparedStatement.class, SshSqlitePreparedStatement.class),
            new InterfaceImplementation(ResultSet.class, BaseResultSet.class),
            new InterfaceImplementation(ResultSet.class, SshSqliteResultSet.class),
            new InterfaceImplementation(ResultSetMetaData.class, BaseResultSetMetaData.class),
            new InterfaceImplementation(ResultSetMetaData.class, SshSqliteResultSetMetaData.class),
            new InterfaceImplementation(DatabaseMetaData.class, BaseDatabaseMetaData.class),
            new InterfaceImplementation(DatabaseMetaData.class, SshSqliteDatabaseMetaData.class)
    );

    @Test
    void java11JdbcInterfacesResolveToConcreteClassMethods() {
        for (InterfaceImplementation implementation : IMPLEMENTATIONS) {
            for (Method interfaceMethod : implementation.jdbcInterface().getMethods()) {
                if (!Modifier.isPublic(interfaceMethod.getModifiers()) || Modifier.isStatic(interfaceMethod.getModifiers())) {
                    continue;
                }
                Method resolved = assertDoesNotThrow(() -> implementation.implementation().getMethod(
                        interfaceMethod.getName(),
                        interfaceMethod.getParameterTypes()
                ));
                assertFalse(Modifier.isAbstract(resolved.getModifiers()),
                        implementation.implementation().getSimpleName() + " leaves " + interfaceMethod + " abstract");
                assertFalse(resolved.getDeclaringClass().isInterface(),
                        implementation.implementation().getSimpleName() + " inherits interface default for " + interfaceMethod);
            }
        }
    }

    @Test
    void metadataUsesSqliteMasterForOldAndNewSQLiteCompatibility() throws Exception {
        String source = Files.readString(Path.of("src/main/java/org/sshsqlite/jdbc/SshSqliteDatabaseMetaData.java"));

        assertTrue(source.contains(".sqlite_master"));
        assertTrue(source.contains(".table_xinfo("));
        assertTrue(source.contains(".index_list("));
        assertTrue(source.contains(".index_xinfo("));
        assertTrue(source.contains(".foreign_key_list("));
        assertFalse(source.contains(".sqlite_schema"));
        assertFalse(source.contains("pragma_table_xinfo"));
        assertFalse(source.contains("pragma_index_list"));
        assertFalse(source.contains("pragma_index_xinfo"));
        assertFalse(source.contains("pragma_foreign_key_list"));
    }

    @Test
    void unsupportedOptionalFeaturesUseSqlFeatureNotSupported() throws Exception {
        BaseConnection connection = new BaseConnection();
        BaseStatement statement = new BaseStatement(connection);
        BasePreparedStatement preparedStatement = new BasePreparedStatement(connection, "select 1");

        assertUnsupported(connection::createBlob);
        assertUnsupported(() -> statement.executeQuery("select 1"));
        assertUnsupported(() -> preparedStatement.setBlob(1, (java.sql.Blob) null));
        assertUnsupported(() -> new BaseDriver().getParentLogger());
    }

    @Test
    void closedConnectionUsesConnectionSqlStateAndAllowsWrapperMethods() throws Exception {
        BaseConnection connection = new BaseConnection();

        connection.close();
        connection.close();

        assertTrue(connection.isClosed());
        assertFalse(connection.isValid(0));
        SQLException exception = assertThrows(SQLException.class, () -> connection.nativeSQL("select 1"));
        assertEquals("08003", exception.getSQLState());
        assertTrue(connection.isWrapperFor(Connection.class));
        assertSame(connection, connection.unwrap(BaseConnection.class));
    }

    @Test
    void closedStatementAndResultSetUseInvalidStateSqlState() throws Exception {
        BaseConnection connection = new BaseConnection();
        BaseStatement statement = new BaseStatement(connection);
        BaseResultSet resultSet = new BaseResultSet(statement, new BaseResultSetMetaData());

        statement.close();
        statement.close();
        resultSet.close();
        resultSet.close();

        assertAll(
                () -> assertTrue(statement.isClosed()),
                () -> assertTrue(resultSet.isClosed()),
                () -> assertEquals("HY010", assertThrows(SQLException.class, statement::getMaxRows).getSQLState()),
                () -> assertEquals("HY010", assertThrows(SQLException.class, resultSet::getType).getSQLState()),
                () -> assertTrue(statement.isWrapperFor(Statement.class)),
                () -> assertTrue(resultSet.isWrapperFor(ResultSet.class))
        );
    }

    @Test
    void wrapperContractRejectsUnsupportedTargetsWithGeneralSqlState() throws Exception {
        BaseResultSetMetaData metaData = new BaseResultSetMetaData();

        assertTrue(metaData.isWrapperFor(ResultSetMetaData.class));
        assertSame(metaData, metaData.unwrap(BaseResultSetMetaData.class));
        SQLException exception = assertThrows(SQLException.class, () -> metaData.unwrap(Connection.class));
        assertEquals("HY000", exception.getSQLState());
    }

    @Test
    void serviceLoaderExposesJdbcDriver() {
        Driver driver = ServiceLoader.load(Driver.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(SshSqliteDriver.class::isInstance)
                .findFirst()
                .orElseThrow();

        assertAll(
                () -> assertTrue(driver.acceptsURL("jdbc:sshsqlite://example/tmp/db.sqlite")),
                () -> assertFalse(driver.acceptsURL("jdbc:sqlite:/tmp/db.sqlite")),
                () -> assertThrows(SQLException.class, () -> driver.connect("jdbc:sshsqlite://example/tmp/db.sqlite", new Properties()))
        );
    }

    @Test
    void inMemoryResultSetBooleanObjectAndDateConversionsUseJdbcSemantics() throws Exception {
        byte[] blob = new byte[] {1, 2};
        InMemoryResultSet resultSet = new InMemoryResultSet(null, List.of(
                new BaseResultSetMetaData.Column("flag", "flag", Types.VARCHAR, "TEXT", String.class.getName(), true),
                new BaseResultSetMetaData.Column("blob", "blob", Types.BLOB, "BLOB", byte[].class.getName(), true),
                new BaseResultSetMetaData.Column("bad_date", "bad_date", Types.VARCHAR, "TEXT", String.class.getName(), true)
        ), List.of(List.of("1", blob, "bad-date")));

        assertTrue(resultSet.next());
        assertTrue(resultSet.getBoolean("flag"));
        assertEquals(Boolean.TRUE, resultSet.getObject("flag", Boolean.class));
        byte[] first = (byte[]) resultSet.getObject("blob", Object.class);
        byte[] second = (byte[]) resultSet.getObject("blob", Object.class);
        assertNotSame(first, second);
        first[0] = 99;
        assertEquals(1, second[0]);
        byte[] untypedFirst = (byte[]) resultSet.getObject("blob");
        byte[] untypedSecond = (byte[]) resultSet.getObject("blob");
        assertNotSame(untypedFirst, untypedSecond);
        untypedFirst[0] = 88;
        assertEquals(1, untypedSecond[0]);
        assertThrows(SQLException.class, () -> resultSet.getObject("bad_date", java.sql.Date.class));
        assertNull(resultSet.getStatement());
    }

    @Test
    void inMemoryResultSetNumericGettersRejectInvalidAndOverflowingConversions() throws Exception {
        InMemoryResultSet resultSet = new InMemoryResultSet(null, List.of(
                new BaseResultSetMetaData.Column("int_overflow", "int_overflow", Types.VARCHAR, "TEXT", String.class.getName(), true),
                new BaseResultSetMetaData.Column("long_fraction", "long_fraction", Types.VARCHAR, "TEXT", String.class.getName(), true),
                new BaseResultSetMetaData.Column("bad_number", "bad_number", Types.VARCHAR, "TEXT", String.class.getName(), true),
                new BaseResultSetMetaData.Column("float_overflow", "float_overflow", Types.VARCHAR, "TEXT", String.class.getName(), true),
                new BaseResultSetMetaData.Column("short_overflow", "short_overflow", Types.VARCHAR, "TEXT", String.class.getName(), true),
                new BaseResultSetMetaData.Column("byte_overflow", "byte_overflow", Types.VARCHAR, "TEXT", String.class.getName(), true),
                new BaseResultSetMetaData.Column("decimal", "decimal", Types.NUMERIC, "NUMERIC", java.math.BigDecimal.class.getName(), true)
        ), List.of(List.of("2147483648", "1.5", "not-number", "1e39", "32768", "128", "123.45")));

        assertTrue(resultSet.next());
        assertThrows(SQLException.class, () -> resultSet.getInt("int_overflow"));
        assertThrows(SQLException.class, () -> resultSet.getLong("long_fraction"));
        assertThrows(SQLException.class, () -> resultSet.getDouble("bad_number"));
        assertThrows(SQLException.class, () -> resultSet.getFloat("float_overflow"));
        assertThrows(SQLException.class, () -> resultSet.getShort("short_overflow"));
        assertThrows(SQLException.class, () -> resultSet.getByte("byte_overflow"));
        assertEquals(new java.math.BigDecimal("123.45"), resultSet.getBigDecimal("decimal"));
    }

    private static void assertUnsupported(SqlRunnable runnable) {
        SQLFeatureNotSupportedException exception = assertThrows(SQLFeatureNotSupportedException.class, runnable::run);
        assertEquals("0A000", exception.getSQLState());
    }

    private static final class InterfaceImplementation {
        private final Class<?> jdbcInterface;
        private final Class<?> implementation;

        private InterfaceImplementation(Class<?> jdbcInterface, Class<?> implementation) {
            this.jdbcInterface = jdbcInterface;
            this.implementation = implementation;
        }

        private Class<?> jdbcInterface() {
            return jdbcInterface;
        }

        private Class<?> implementation() {
            return implementation;
        }
    }

    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws Exception;
    }
}
