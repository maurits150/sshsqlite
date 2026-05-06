package org.sshsqlite.jdbc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("legacyHelper")
class JdbcMetadataIntegrationTest {
    @TempDir Path tempDir;

    @Test
    void metadataResultSetsExposeDocumentedShapes() throws Exception {
        Path db = createDatabase();

        try (Connection connection = DriverManager.getConnection(url(db), properties(db))) {
            DatabaseMetaData meta = connection.getMetaData();

            assertShape(meta.getTables(null, null, "%", null), List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS", "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SELF_REFERENCING_COL_NAME", "REF_GENERATION"), List.of(Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR));
            assertShape(meta.getColumns(null, null, "%", "%"), List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE", "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE", "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE", "SOURCE_DATA_TYPE", "IS_AUTOINCREMENT", "IS_GENERATEDCOLUMN"), null);
            assertShape(meta.getPrimaryKeys(null, "main", "child"), List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME"), null);
            assertShape(meta.getIndexInfo(null, "main", "child", false, false), List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "NON_UNIQUE", "INDEX_QUALIFIER", "INDEX_NAME", "TYPE", "ORDINAL_POSITION", "COLUMN_NAME", "ASC_OR_DESC", "CARDINALITY", "PAGES", "FILTER_CONDITION"), null);
            assertShape(meta.getImportedKeys(null, "main", "child"), List.of("PKTABLE_CAT", "PKTABLE_SCHEM", "PKTABLE_NAME", "PKCOLUMN_NAME", "FKTABLE_CAT", "FKTABLE_SCHEM", "FKTABLE_NAME", "FKCOLUMN_NAME", "KEY_SEQ", "UPDATE_RULE", "DELETE_RULE", "FK_NAME", "PK_NAME", "DEFERRABILITY"), null);
            assertShape(meta.getSchemas(), List.of("TABLE_SCHEM", "TABLE_CATALOG"), null);
            assertShape(meta.getCatalogs(), List.of("TABLE_CAT"), null);
            assertShape(meta.getTableTypes(), List.of("TABLE_TYPE"), null);
            assertShape(meta.getTypeInfo(), List.of("TYPE_NAME", "DATA_TYPE", "PRECISION", "LITERAL_PREFIX", "LITERAL_SUFFIX", "CREATE_PARAMS", "NULLABLE", "CASE_SENSITIVE", "SEARCHABLE", "UNSIGNED_ATTRIBUTE", "FIXED_PREC_SCALE", "AUTO_INCREMENT", "LOCAL_TYPE_NAME", "MINIMUM_SCALE", "MAXIMUM_SCALE", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "NUM_PREC_RADIX"), null);
            assertShape(meta.getProcedures(null, null, "%"), List.of("PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "RESERVED1", "RESERVED2", "RESERVED3", "REMARKS", "PROCEDURE_TYPE", "SPECIFIC_NAME"), null);
            assertShape(meta.getProcedureColumns(null, null, "%", "%"), List.of("PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "COLUMN_NAME", "COLUMN_TYPE", "DATA_TYPE", "TYPE_NAME", "PRECISION", "LENGTH", "SCALE", "RADIX", "NULLABLE", "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE", "SPECIFIC_NAME"), null);
            assertShape(meta.getFunctions(null, null, "%"), List.of("FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "REMARKS", "FUNCTION_TYPE", "SPECIFIC_NAME"), null);
            assertShape(meta.getFunctionColumns(null, null, "%", "%"), List.of("FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "COLUMN_NAME", "COLUMN_TYPE", "DATA_TYPE", "TYPE_NAME", "PRECISION", "LENGTH", "SCALE", "RADIX", "NULLABLE", "REMARKS", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE", "SPECIFIC_NAME"), null);
            assertShape(meta.getUDTs(null, null, "%", null), List.of("TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "CLASS_NAME", "DATA_TYPE", "REMARKS", "BASE_TYPE"), null);
        }
    }

    @Test
    void metadataIntrospectsSqliteSchemasSafely() throws Exception {
        Path db = createDatabase();

        try (Connection connection = DriverManager.getConnection(url(db), properties(db))) {
            DatabaseMetaData meta = connection.getMetaData();

            List<String> tables = names(meta.getTables(null, "main", "%", null), "TABLE_NAME");
            assertTrue(tables.contains("parent"));
            assertTrue(tables.contains("child"));
            assertTrue(tables.contains("child_view"));
            assertTrue(tables.contains("weird table"));
            assertFalse(tables.contains("sqlite_sequence"));

            assertEquals(List.of("weird table"), names(meta.getTables(null, "main", "weird%", new String[]{"TABLE"}), "TABLE_NAME"));
            assertEquals(List.of("main"), names(meta.getSchemas(null, "ma_n"), "TABLE_SCHEM"));
            assertTrue(names(meta.getSchemas(), "TABLE_SCHEM").contains("temp"));

            List<String> childColumns = names(meta.getColumns(null, "main", "child", "%"), "COLUMN_NAME");
            assertTrue(childColumns.contains("note_len"));
            try (ResultSet columns = meta.getColumns(null, "main", "child", "note\\_len")) {
                assertTrue(columns.next());
                assertEquals("note_len", columns.getString("COLUMN_NAME"));
                assertEquals("NO", columns.getString("IS_GENERATEDCOLUMN"));
            }

            try (ResultSet columns = meta.getColumns(null, "main", "declared_types", "%")) {
                java.util.Map<String, String> typeNames = new java.util.LinkedHashMap<>();
                java.util.Map<String, Integer> dataTypes = new java.util.LinkedHashMap<>();
                while (columns.next()) {
                    typeNames.put(columns.getString("COLUMN_NAME"), columns.getString("TYPE_NAME"));
                    dataTypes.put(columns.getString("COLUMN_NAME"), columns.getInt("DATA_TYPE"));
                }
                assertEquals("VARCHAR(255)", typeNames.get("name"));
                assertEquals(Types.VARCHAR, dataTypes.get("name"));
                assertEquals("DATETIME", typeNames.get("created_at"));
                assertEquals(Types.NUMERIC, dataTypes.get("created_at"));
                assertEquals("BOOLEAN", typeNames.get("enabled"));
                assertEquals(Types.NUMERIC, dataTypes.get("enabled"));
                assertEquals("DECIMAL(10,2)", typeNames.get("price"));
                assertEquals(Types.NUMERIC, dataTypes.get("price"));
                assertEquals("JSON", typeNames.get("payload"));
                assertEquals(Types.VARCHAR, dataTypes.get("payload"));
            }

            List<String> typeInfo = names(meta.getTypeInfo(), "TYPE_NAME");
            assertTrue(typeInfo.contains("VARCHAR"));
            assertTrue(typeInfo.contains("DATETIME"));
            assertTrue(typeInfo.contains("BOOLEAN"));
            assertTrue(typeInfo.contains("DECIMAL"));
            assertTrue(typeInfo.contains("JSON"));

            try (ResultSet keys = meta.getPrimaryKeys(null, "main", "weird table")) {
                assertTrue(keys.next());
                assertEquals("select", keys.getString("COLUMN_NAME"));
                assertEquals(1, keys.getShort("KEY_SEQ"));
                assertTrue(keys.next());
                assertEquals("a\"b", keys.getString("COLUMN_NAME"));
                assertEquals(2, keys.getShort("KEY_SEQ"));
            }

            try (ResultSet indexes = meta.getIndexInfo(null, "main", "child", false, false)) {
                boolean found = false;
                while (indexes.next()) {
                    if ("idx_child_amount".equals(indexes.getString("INDEX_NAME"))) {
                        found = true;
                        assertEquals("amount", indexes.getString("COLUMN_NAME"));
                        assertEquals("D", indexes.getString("ASC_OR_DESC"));
                    }
                }
                assertTrue(found);
            }

            try (ResultSet imported = meta.getImportedKeys(null, "main", "child")) {
                assertTrue(imported.next());
                assertEquals("parent", imported.getString("PKTABLE_NAME"));
                assertEquals("parent_id", imported.getString("FKCOLUMN_NAME"));
                assertEquals(DatabaseMetaData.importedKeyCascade, imported.getShort("DELETE_RULE"));
                assertEquals(DatabaseMetaData.importedKeyRestrict, imported.getShort("UPDATE_RULE"));
            }
        }
    }

    @Test
    void conservativeCapabilityMethodsReturnToolFriendlyConstants() throws Exception {
        Path db = createDatabase();

        try (Connection connection = DriverManager.getConnection(url(db), properties(db))) {
            DatabaseMetaData meta = connection.getMetaData();

            assertEquals("SQLite", meta.getDatabaseProductName());
            assertEquals("SSHSQLite JDBC Driver", meta.getDriverName());
            assertEquals("\"", meta.getIdentifierQuoteString());
            assertEquals("\\", meta.getSearchStringEscape());
            assertTrue(meta.getURL().contains("<path-redacted>"));
            assertEquals("local", meta.getUserName());
            assertFalse(meta.supportsMultipleResultSets());
            assertFalse(meta.supportsBatchUpdates());
            assertTrue(meta.supportsGetGeneratedKeys());
            assertFalse(meta.supportsTransactions());
            assertTrue(meta.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY));
            assertTrue(meta.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
            assertEquals(ResultSet.CLOSE_CURSORS_AT_COMMIT, meta.getResultSetHoldability());
        }
    }

    private static void assertShape(ResultSet rs, List<String> names, List<Integer> types) throws Exception {
        try (rs) {
            ResultSetMetaData meta = rs.getMetaData();
            assertEquals(names.size(), meta.getColumnCount());
            for (int i = 0; i < names.size(); i++) {
                assertEquals(names.get(i), meta.getColumnName(i + 1));
                if (types != null) assertEquals(types.get(i), meta.getColumnType(i + 1), names.get(i));
            }
        }
    }

    private static List<String> names(ResultSet rs, String column) throws Exception {
        try (rs) {
            List<String> values = new ArrayList<>();
            while (rs.next()) values.add(rs.getString(column));
            return values;
        }
    }

    private Path createDatabase() throws Exception {
        Path db = tempDir.resolve("metadata.db");
        String sql = "PRAGMA foreign_keys=ON;" +
                "CREATE TABLE parent(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE);" +
                "CREATE TABLE child(id INTEGER PRIMARY KEY, parent_id INTEGER NOT NULL REFERENCES parent(id) ON DELETE CASCADE ON UPDATE RESTRICT, amount NUMERIC(10,2), note TEXT, note_len INTEGER);" +
                "CREATE TABLE declared_types(name VARCHAR(255), created_at DATETIME, enabled BOOLEAN, price DECIMAL(10,2), payload JSON);" +
                "CREATE INDEX idx_child_amount ON child(amount DESC);" +
                "CREATE VIEW child_view AS SELECT id, note FROM child;" +
                "CREATE TABLE \"weird table\"(\"select\" TEXT, \"a\"\"b\" INTEGER, PRIMARY KEY(\"select\", \"a\"\"b\")) WITHOUT ROWID;";
        Process process = new ProcessBuilder("sqlite3", db.toString(), sql).start();
        assertEquals(0, process.waitFor());
        return db;
    }

    private String url(Path db) {
        return "jdbc:sshsqlite://local" + db;
    }

    private Properties properties(Path db) throws Exception {
        Path allowlist = tempDir.resolve("allowlist.json");
        Files.writeString(allowlist, "{\"version\":1,\"databases\":[{\"path\":\"" +
                db.toRealPath().toString().replace("\\", "\\\\").replace("\"", "\\\"") +
                "\",\"mode\":\"readonly\"}]}");
        Properties props = new Properties();
        props.setProperty("ssh.user", "local");
        props.setProperty("helper.transport", "local");
        props.setProperty("helper.localPath", System.getProperty("sshsqlite.helperBinary"));
        props.setProperty("helper.localAllowlist", allowlist.toString());
        props.setProperty("helper.startupTimeoutMs", "10000");
        props.setProperty("stderr.maxBufferedBytes", "4096");
        return props;
    }
}
