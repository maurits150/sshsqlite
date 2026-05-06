package org.sshsqlite.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SshSqliteDatabaseMetaData extends BaseDatabaseMetaData {
    private final SshSqliteConnection connection;

    public SshSqliteDatabaseMetaData(BaseConnection connection) {
        super(connection);
        this.connection = (SshSqliteConnection) connection;
    }

    @Override public String getURL() throws SQLException { checkOpen(); return connection.redactedUrl(); }
    @Override public String getUserName() throws SQLException { checkOpen(); return connection.userName(); }
    @Override public String getDatabaseProductVersion() throws SQLException { checkOpen(); return connection.sqliteVersion(); }
    @Override public int getDatabaseMajorVersion() throws SQLException { return versionPart(0); }
    @Override public int getDatabaseMinorVersion() throws SQLException { return versionPart(1); }
    @Override public int getJDBCMajorVersion() throws SQLException { checkOpen(); return 4; }
    @Override public int getJDBCMinorVersion() throws SQLException { checkOpen(); return 2; }
    @Override public int getSQLStateType() throws SQLException { checkOpen(); return DatabaseMetaData.sqlStateSQL; }
    @Override public String getIdentifierQuoteString() throws SQLException { checkOpen(); return "\""; }
    @Override public String getSearchStringEscape() throws SQLException { checkOpen(); return "\\"; }
    @Override public String getSQLKeywords() throws SQLException { checkOpen(); return ""; }
    @Override public String getNumericFunctions() throws SQLException { checkOpen(); return ""; }
    @Override public String getStringFunctions() throws SQLException { checkOpen(); return ""; }
    @Override public String getSystemFunctions() throws SQLException { checkOpen(); return ""; }
    @Override public String getTimeDateFunctions() throws SQLException { checkOpen(); return ""; }
    @Override public String getExtraNameCharacters() throws SQLException { checkOpen(); return ""; }
    @Override public boolean allProceduresAreCallable() throws SQLException { checkOpen(); return false; }
    @Override public boolean allTablesAreSelectable() throws SQLException { checkOpen(); return true; }
    @Override public boolean nullsAreSortedHigh() throws SQLException { checkOpen(); return false; }
    @Override public boolean nullsAreSortedLow() throws SQLException { checkOpen(); return true; }
    @Override public boolean nullsAreSortedAtStart() throws SQLException { checkOpen(); return false; }
    @Override public boolean nullsAreSortedAtEnd() throws SQLException { checkOpen(); return false; }
    @Override public boolean usesLocalFiles() throws SQLException { checkOpen(); return false; }
    @Override public boolean usesLocalFilePerTable() throws SQLException { checkOpen(); return false; }
    @Override public boolean supportsMixedCaseIdentifiers() throws SQLException { checkOpen(); return true; }
    @Override public boolean storesUpperCaseIdentifiers() throws SQLException { checkOpen(); return false; }
    @Override public boolean storesLowerCaseIdentifiers() throws SQLException { checkOpen(); return false; }
    @Override public boolean storesMixedCaseIdentifiers() throws SQLException { checkOpen(); return true; }
    @Override public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException { checkOpen(); return true; }
    @Override public boolean storesUpperCaseQuotedIdentifiers() throws SQLException { checkOpen(); return false; }
    @Override public boolean storesLowerCaseQuotedIdentifiers() throws SQLException { checkOpen(); return false; }
    @Override public boolean storesMixedCaseQuotedIdentifiers() throws SQLException { checkOpen(); return true; }
    @Override public boolean supportsLikeEscapeClause() throws SQLException { checkOpen(); return true; }
    @Override public boolean supportsColumnAliasing() throws SQLException { checkOpen(); return true; }
    @Override public boolean nullPlusNonNullIsNull() throws SQLException { checkOpen(); return true; }
    @Override public boolean supportsMultipleResultSets() throws SQLException { checkOpen(); return false; }
    @Override public boolean supportsMultipleTransactions() throws SQLException { checkOpen(); return false; }
    @Override public boolean supportsNonNullableColumns() throws SQLException { checkOpen(); return true; }
    @Override public boolean supportsStoredProcedures() throws SQLException { checkOpen(); return false; }
    @Override public boolean supportsTransactions() throws SQLException { checkOpen(); return !connection.configuredReadOnly(); }
    @Override public int getDefaultTransactionIsolation() throws SQLException { checkOpen(); return Connection.TRANSACTION_SERIALIZABLE; }
    @Override public boolean supportsTransactionIsolationLevel(int level) throws SQLException { checkOpen(); return level == Connection.TRANSACTION_SERIALIZABLE; }
    @Override public boolean supportsDataDefinitionAndDataManipulationTransactions() throws SQLException { checkOpen(); return false; }
    @Override public boolean supportsDataManipulationTransactionsOnly() throws SQLException { checkOpen(); return !connection.configuredReadOnly(); }
    @Override public boolean dataDefinitionCausesTransactionCommit() throws SQLException { checkOpen(); return false; }
    @Override public boolean dataDefinitionIgnoredInTransactions() throws SQLException { checkOpen(); return false; }
    @Override public int getResultSetHoldability() throws SQLException { checkOpen(); return ResultSet.CLOSE_CURSORS_AT_COMMIT; }
    @Override public boolean supportsResultSetType(int type) throws SQLException { checkOpen(); return type == ResultSet.TYPE_FORWARD_ONLY; }
    @Override public boolean supportsResultSetConcurrency(int type, int concurrency) throws SQLException { checkOpen(); return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY; }
    @Override public boolean supportsResultSetHoldability(int holdability) throws SQLException { checkOpen(); return holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT; }
    @Override public boolean supportsBatchUpdates() throws SQLException { checkOpen(); return false; }
    @Override public boolean supportsMultipleOpenResults() throws SQLException { checkOpen(); return false; }
    @Override public boolean supportsGetGeneratedKeys() throws SQLException { checkOpen(); return true; }
    @Override public boolean generatedKeyAlwaysReturned() throws SQLException { checkOpen(); return false; }
    @Override public boolean supportsSavepoints() throws SQLException { checkOpen(); return false; }
    @Override public boolean supportsNamedParameters() throws SQLException { checkOpen(); return false; }
    @Override public boolean supportsStatementPooling() throws SQLException { checkOpen(); return false; }
    @Override public RowIdLifetime getRowIdLifetime() throws SQLException { checkOpen(); return RowIdLifetime.ROWID_UNSUPPORTED; }
    @Override public boolean autoCommitFailureClosesAllResultSets() throws SQLException { checkOpen(); return true; }

    @Override public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types) throws SQLException {
        checkOpen();
        Set<String> typeFilter = types == null ? null : new HashSet<>(List.of(types));
        List<List<Object>> rows = new ArrayList<>();
        if (catalog != null) return result(TABLES, rows);
        for (String schema : schemas(schemaPattern)) {
            for (TableInfo table : tables(schema)) {
                if (!matches(table.name, tableNamePattern) || (typeFilter != null && !typeFilter.contains(table.jdbcType))) continue;
                rows.add(row(null, schema, table.name, table.jdbcType, null, null, null, null, null, null));
            }
        }
        rows.sort(Comparator.comparing((List<Object> r) -> str(r.get(3))).thenComparing(r -> str(r.get(1))).thenComparing(r -> str(r.get(2))));
        return result(TABLES, rows);
    }

    @Override public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException {
        checkOpen();
        List<List<Object>> rows = new ArrayList<>();
        if (catalog != null) return result(COLUMNS, rows);
        for (String schema : schemas(schemaPattern)) {
            for (TableInfo table : tables(schema)) {
                if (!matches(table.name, tableNamePattern)) continue;
                int pkCount = 0;
                List<ColumnInfo> columns = columnInfos(schema, table.name);
                for (ColumnInfo column : columns) if (column.pk > 0) pkCount++;
                for (ColumnInfo column : columns) {
                    if (!matches(column.name, columnNamePattern)) continue;
                    int type = sqlType(column.typeName);
                    Integer size = declaredSize(column.typeName, 0);
                    Integer scale = declaredSize(column.typeName, 1);
                    boolean nonNull = column.notNull || column.pk > 0;
                    rows.add(row(null, schema, table.name, column.name, type, typeName(column.typeName), size, null, scale,
                            numericRadix(type), nonNull ? DatabaseMetaData.columnNoNulls : DatabaseMetaData.columnNullable, null,
                            column.defaultValue, null, null, type == Types.VARCHAR ? size : null, column.cid + 1,
                            nonNull ? "NO" : "YES", null, null, null, null,
                            column.pk > 0 && pkCount == 1 && column.typeName.toUpperCase(Locale.ROOT).contains("INT") ? "YES" : "NO",
                            column.hidden == 2 || column.hidden == 3 ? "YES" : "NO"));
                }
            }
        }
        rows.sort(Comparator.comparing((List<Object> r) -> str(r.get(1))).thenComparing(r -> str(r.get(2))).thenComparingInt(r -> ((Number) r.get(16)).intValue()));
        return result(COLUMNS, rows);
    }

    @Override public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        checkOpen();
        List<List<Object>> rows = new ArrayList<>();
        if (catalog != null || table == null) return result(PRIMARY_KEYS, rows);
        String actualSchema = schema == null || schema.isBlank() ? "main" : schema;
        for (ColumnInfo column : columnInfos(actualSchema, table)) {
            if (column.pk > 0) rows.add(row(null, actualSchema, table, column.name, (short) column.pk, null));
        }
        rows.sort(Comparator.comparingInt(r -> ((Number) r.get(4)).intValue()));
        return result(PRIMARY_KEYS, rows);
    }

    @Override public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate) throws SQLException {
        checkOpen();
        List<List<Object>> rows = new ArrayList<>();
        if (catalog != null || table == null) return result(INDEX_INFO, rows);
        String actualSchema = schema == null || schema.isBlank() ? "main" : schema;
        for (IndexInfo index : indexInfos(actualSchema, table)) {
            if (unique && !index.unique) continue;
            for (IndexColumn column : indexColumns(actualSchema, index.name)) {
                rows.add(row(null, actualSchema, table, !index.unique, null, index.name, (short) DatabaseMetaData.tableIndexOther,
                        (short) (column.seqno + 1), column.name, column.desc ? "D" : "A", null, null, null));
            }
        }
        rows.sort(Comparator.comparing((List<Object> r) -> (Boolean) r.get(3)).thenComparing(r -> str(r.get(5))).thenComparingInt(r -> ((Number) r.get(7)).intValue()));
        return result(INDEX_INFO, rows);
    }

    @Override public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
        checkOpen();
        List<List<Object>> rows = new ArrayList<>();
        if (catalog != null || table == null) return result(IMPORTED_KEYS, rows);
        String actualSchema = schema == null || schema.isBlank() ? "main" : schema;
        for (ForeignKey key : foreignKeys(actualSchema, table)) {
            rows.add(row(null, actualSchema, key.pkTable, key.pkColumn, null, actualSchema, table, key.fkColumn,
                    (short) (key.seq + 1), fkRule(key.updateRule), fkRule(key.deleteRule), "fk_" + table + "_" + key.id, null,
                    (short) DatabaseMetaData.importedKeyNotDeferrable));
        }
        rows.sort(Comparator.comparing((List<Object> r) -> str(r.get(2))).thenComparingInt(r -> ((Number) r.get(8)).intValue()));
        return result(IMPORTED_KEYS, rows);
    }

    @Override public ResultSet getSchemas() throws SQLException { return getSchemas(null, null); }

    @Override public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        checkOpen();
        List<List<Object>> rows = new ArrayList<>();
        if (catalog != null) return result(SCHEMAS, rows);
        for (String schema : schemas(schemaPattern)) rows.add(row(schema, null));
        rows.sort(Comparator.comparing(r -> str(r.get(0))));
        return result(SCHEMAS, rows);
    }

    @Override public ResultSet getCatalogs() throws SQLException { checkOpen(); return result(CATALOGS, List.of()); }
    @Override public ResultSet getTableTypes() throws SQLException { checkOpen(); return result(TABLE_TYPES, List.of(row("TABLE"), row("VIEW"))); }

    @Override public ResultSet getTypeInfo() throws SQLException {
        checkOpen();
        return result(TYPE_INFO, List.of(
                typeRow("INTEGER", Types.BIGINT, true, false),
                typeRow("INT", Types.BIGINT, true, false),
                typeRow("BIGINT", Types.BIGINT, false, false),
                typeRow("SMALLINT", Types.INTEGER, false, false),
                typeRow("TINYINT", Types.INTEGER, false, false),
                typeRow("BOOLEAN", Types.NUMERIC, false, false),
                typeRow("REAL", Types.DOUBLE, false, false),
                typeRow("DOUBLE", Types.DOUBLE, false, false),
                typeRow("DOUBLE PRECISION", Types.DOUBLE, false, false),
                typeRow("FLOAT", Types.DOUBLE, false, false),
                typeRow("TEXT", Types.VARCHAR, false, true),
                typeRow("VARCHAR", Types.VARCHAR, false, true),
                typeRow("CHAR", Types.VARCHAR, false, true),
                typeRow("CHARACTER", Types.VARCHAR, false, true),
                typeRow("NCHAR", Types.VARCHAR, false, true),
                typeRow("NVARCHAR", Types.VARCHAR, false, true),
                typeRow("CLOB", Types.VARCHAR, false, true),
                typeRow("DATE", Types.NUMERIC, false, false),
                typeRow("TIME", Types.NUMERIC, false, false),
                typeRow("DATETIME", Types.NUMERIC, false, false),
                typeRow("TIMESTAMP", Types.NUMERIC, false, false),
                typeRow("JSON", Types.VARCHAR, false, true),
                typeRow("BLOB", Types.BLOB, false, false),
                typeRow("VARBINARY", Types.BLOB, false, false),
                typeRow("NUMERIC", Types.NUMERIC, false, false),
                typeRow("DECIMAL", Types.NUMERIC, false, false),
                typeRow("NUMBER", Types.NUMERIC, false, false),
                typeRow("NULL", Types.NULL, false, false)
        ));
    }

    @Override public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern) throws SQLException { checkOpen(); return result(PROCEDURES, List.of()); }
    @Override public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern) throws SQLException { checkOpen(); return result(PROCEDURE_COLUMNS, List.of()); }
    @Override public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern) throws SQLException { checkOpen(); return result(FUNCTIONS, List.of()); }
    @Override public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern, String columnNamePattern) throws SQLException { checkOpen(); return result(FUNCTION_COLUMNS, List.of()); }
    @Override public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types) throws SQLException { checkOpen(); return result(UDTS, List.of()); }

    private List<String> schemas(String pattern) throws SQLException {
        List<String> result = new ArrayList<>();
        for (List<Object> row : query("PRAGMA database_list")) {
            String schema = str(row.get(1));
            if (matches(schema, pattern)) result.add(schema);
        }
        if (!result.contains("main") && matches("main", pattern)) result.add("main");
        if (!result.contains("temp") && matches("temp", pattern)) result.add("temp");
        return result;
    }

    private List<TableInfo> tables(String schema) throws SQLException {
        List<TableInfo> result = new ArrayList<>();
        String sql = "SELECT name, type FROM " + quoteIdentifier(schema) + ".sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\'";
        for (List<Object> row : query(sql)) {
            String type = str(row.get(1));
            result.add(new TableInfo(str(row.get(0)), "view".equals(type) ? "VIEW" : "TABLE"));
        }
        return result;
    }

    private List<ColumnInfo> columnInfos(String schema, String table) throws SQLException {
        List<ColumnInfo> result = new ArrayList<>();
        String sql = "PRAGMA " + quoteIdentifier(schema) + ".table_xinfo(" + literal(table) + ")";
        for (List<Object> row : query(sql)) result.add(new ColumnInfo(((Number) row.get(0)).intValue(), str(row.get(1)), str(row.get(2)), number(row.get(3)) != 0, nullableString(row.get(4)), (int) number(row.get(5)), (int) number(row.get(6))));
        return result;
    }

    private List<IndexInfo> indexInfos(String schema, String table) throws SQLException {
        List<IndexInfo> result = new ArrayList<>();
        String sql = "PRAGMA " + quoteIdentifier(schema) + ".index_list(" + literal(table) + ")";
        for (List<Object> row : query(sql)) result.add(new IndexInfo(str(row.get(1)), number(row.get(2)) != 0));
        return result;
    }

    private List<IndexColumn> indexColumns(String schema, String index) throws SQLException {
        List<IndexColumn> result = new ArrayList<>();
        String sql = "PRAGMA " + quoteIdentifier(schema) + ".index_xinfo(" + literal(index) + ")";
        for (List<Object> row : query(sql)) {
            if (number(row.get(5)) == 1) result.add(new IndexColumn((int) number(row.get(0)), nullableString(row.get(2)), number(row.get(3)) != 0));
        }
        return result;
    }

    private List<ForeignKey> foreignKeys(String schema, String table) throws SQLException {
        List<ForeignKey> result = new ArrayList<>();
        String sql = "PRAGMA " + quoteIdentifier(schema) + ".foreign_key_list(" + literal(table) + ")";
        for (List<Object> row : query(sql)) result.add(new ForeignKey((int) number(row.get(0)), (int) number(row.get(1)), str(row.get(2)), nullableString(row.get(4)), str(row.get(3)), str(row.get(5)), str(row.get(6))));
        return result;
    }

    private List<List<Object>> query(String sql) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            int count = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<Object> row = new ArrayList<>(count);
                for (int i = 1; i <= count; i++) row.add(rs.getObject(i));
                rows.add(row);
            }
        }
        return rows;
    }

    private ResultSet result(List<BaseResultSetMetaData.Column> columns, List<List<Object>> rows) {
        return new InMemoryResultSet(null, columns, rows);
    }

    private int versionPart(int index) throws SQLException {
        checkOpen();
        String[] parts = connection.sqliteVersion().split("\\.");
        if (parts.length <= index) return 0;
        try { return Integer.parseInt(parts[index].replaceAll("\\D.*", "")); } catch (NumberFormatException e) { return 0; }
    }

    private static boolean matches(String value, String pattern) {
        if (pattern == null) return true;
        StringBuilder regex = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (escaped) { regex.append(java.util.regex.Pattern.quote(String.valueOf(ch))); escaped = false; }
            else if (ch == '\\') escaped = true;
            else if (ch == '%') regex.append(".*");
            else if (ch == '_') regex.append('.');
            else regex.append(java.util.regex.Pattern.quote(String.valueOf(ch)));
        }
        return java.util.regex.Pattern.compile(regex.toString(), java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL).matcher(value).matches();
    }

    static String quoteIdentifier(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }
    static String literal(String value) { return value == null ? "NULL" : "'" + value.replace("'", "''") + "'"; }
    private static String str(Object value) { return value == null ? "" : value.toString(); }
    private static String nullableString(Object value) { return value == null ? null : value.toString(); }
    private static long number(Object value) { return value instanceof Number ? ((Number) value).longValue() : Long.parseLong(value.toString()); }
    private static List<Object> row(Object... values) { return Arrays.asList(values); }
    private static List<Object> typeRow(String name, int type, boolean autoIncrement, boolean text) { return row(name, type, null, text ? "'" : null, text ? "'" : null, null, (short) DatabaseMetaData.typeNullable, text, (short) DatabaseMetaData.typeSearchable, false, false, autoIncrement, null, (short) 0, null, null, null, type == Types.NULL ? null : 10); }
    private static Integer numericRadix(int type) {
        switch (type) {
            case Types.BIGINT:
            case Types.INTEGER:
            case Types.DOUBLE:
            case Types.REAL:
            case Types.NUMERIC:
            case Types.DECIMAL:
                return 10;
            default:
                return null;
        }
    }
    private static String typeName(String declared) { return declared == null || declared.isBlank() ? "BLOB" : declared; }
    private static int sqlType(String declared) {
        String t = declared == null ? "" : declared.toUpperCase(Locale.ROOT);
        if (t.contains("INT")) return Types.BIGINT;
        if (t.contains("CHAR") || t.contains("CLOB") || t.contains("TEXT")) return Types.VARCHAR;
        if (t.contains("REAL") || t.contains("FLOA") || t.contains("DOUB")) return Types.DOUBLE;
        if (t.contains("NUM") || t.contains("DEC") || t.contains("BOOL") || t.contains("DATE")) return Types.NUMERIC;
        return Types.BLOB;
    }
    private static Integer declaredSize(String type, int group) {
        if (type == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\((\\d+)(?:\\s*,\\s*(\\d+))?\\)").matcher(type);
        if (!m.find() || m.group(group + 1) == null) return null;
        return Integer.parseInt(m.group(group + 1));
    }
    private static short fkRule(String action) {
        switch (action == null ? "" : action.toUpperCase(Locale.ROOT)) {
            case "CASCADE": return DatabaseMetaData.importedKeyCascade;
            case "RESTRICT": return DatabaseMetaData.importedKeyRestrict;
            case "SET NULL": return DatabaseMetaData.importedKeySetNull;
            case "SET DEFAULT": return DatabaseMetaData.importedKeySetDefault;
            default: return DatabaseMetaData.importedKeyNoAction;
        }
    }
    private static BaseResultSetMetaData.Column col(String name, int type) { return new BaseResultSetMetaData.Column(name, name, type, typeNameFor(type), className(type), true); }
    private static String typeNameFor(int type) {
        switch (type) {
            case Types.INTEGER: return "INTEGER";
            case Types.SMALLINT: return "SMALLINT";
            case Types.BIGINT: return "BIGINT";
            case Types.BOOLEAN: return "BOOLEAN";
            default: return "VARCHAR";
        }
    }
    private static String className(int type) {
        switch (type) {
            case Types.INTEGER: return Integer.class.getName();
            case Types.SMALLINT: return Short.class.getName();
            case Types.BIGINT: return Long.class.getName();
            case Types.BOOLEAN: return Boolean.class.getName();
            default: return String.class.getName();
        }
    }

    private static final class TableInfo {
        final String name;
        final String jdbcType;
        TableInfo(String name, String jdbcType) { this.name = name; this.jdbcType = jdbcType; }
    }
    private static final class ColumnInfo {
        final int cid;
        final String name;
        final String typeName;
        final boolean notNull;
        final String defaultValue;
        final int pk;
        final int hidden;
        ColumnInfo(int cid, String name, String typeName, boolean notNull, String defaultValue, int pk, int hidden) {
            this.cid = cid; this.name = name; this.typeName = typeName; this.notNull = notNull; this.defaultValue = defaultValue; this.pk = pk; this.hidden = hidden;
        }
    }
    private static final class IndexInfo {
        final String name;
        final boolean unique;
        IndexInfo(String name, boolean unique) { this.name = name; this.unique = unique; }
    }
    private static final class IndexColumn {
        final int seqno;
        final String name;
        final boolean desc;
        IndexColumn(int seqno, String name, boolean desc) { this.seqno = seqno; this.name = name; this.desc = desc; }
    }
    private static final class ForeignKey {
        final int id;
        final int seq;
        final String pkTable;
        final String pkColumn;
        final String fkColumn;
        final String updateRule;
        final String deleteRule;
        ForeignKey(int id, int seq, String pkTable, String pkColumn, String fkColumn, String updateRule, String deleteRule) {
            this.id = id; this.seq = seq; this.pkTable = pkTable; this.pkColumn = pkColumn; this.fkColumn = fkColumn; this.updateRule = updateRule; this.deleteRule = deleteRule;
        }
    }

    private static final List<BaseResultSetMetaData.Column> TABLES = List.of(col("TABLE_CAT", Types.VARCHAR), col("TABLE_SCHEM", Types.VARCHAR), col("TABLE_NAME", Types.VARCHAR), col("TABLE_TYPE", Types.VARCHAR), col("REMARKS", Types.VARCHAR), col("TYPE_CAT", Types.VARCHAR), col("TYPE_SCHEM", Types.VARCHAR), col("TYPE_NAME", Types.VARCHAR), col("SELF_REFERENCING_COL_NAME", Types.VARCHAR), col("REF_GENERATION", Types.VARCHAR));
    private static final List<BaseResultSetMetaData.Column> COLUMNS = List.of(col("TABLE_CAT", Types.VARCHAR), col("TABLE_SCHEM", Types.VARCHAR), col("TABLE_NAME", Types.VARCHAR), col("COLUMN_NAME", Types.VARCHAR), col("DATA_TYPE", Types.INTEGER), col("TYPE_NAME", Types.VARCHAR), col("COLUMN_SIZE", Types.INTEGER), col("BUFFER_LENGTH", Types.INTEGER), col("DECIMAL_DIGITS", Types.INTEGER), col("NUM_PREC_RADIX", Types.INTEGER), col("NULLABLE", Types.INTEGER), col("REMARKS", Types.VARCHAR), col("COLUMN_DEF", Types.VARCHAR), col("SQL_DATA_TYPE", Types.INTEGER), col("SQL_DATETIME_SUB", Types.INTEGER), col("CHAR_OCTET_LENGTH", Types.INTEGER), col("ORDINAL_POSITION", Types.INTEGER), col("IS_NULLABLE", Types.VARCHAR), col("SCOPE_CATALOG", Types.VARCHAR), col("SCOPE_SCHEMA", Types.VARCHAR), col("SCOPE_TABLE", Types.VARCHAR), col("SOURCE_DATA_TYPE", Types.SMALLINT), col("IS_AUTOINCREMENT", Types.VARCHAR), col("IS_GENERATEDCOLUMN", Types.VARCHAR));
    private static final List<BaseResultSetMetaData.Column> PRIMARY_KEYS = List.of(col("TABLE_CAT", Types.VARCHAR), col("TABLE_SCHEM", Types.VARCHAR), col("TABLE_NAME", Types.VARCHAR), col("COLUMN_NAME", Types.VARCHAR), col("KEY_SEQ", Types.SMALLINT), col("PK_NAME", Types.VARCHAR));
    private static final List<BaseResultSetMetaData.Column> INDEX_INFO = List.of(col("TABLE_CAT", Types.VARCHAR), col("TABLE_SCHEM", Types.VARCHAR), col("TABLE_NAME", Types.VARCHAR), col("NON_UNIQUE", Types.BOOLEAN), col("INDEX_QUALIFIER", Types.VARCHAR), col("INDEX_NAME", Types.VARCHAR), col("TYPE", Types.SMALLINT), col("ORDINAL_POSITION", Types.SMALLINT), col("COLUMN_NAME", Types.VARCHAR), col("ASC_OR_DESC", Types.VARCHAR), col("CARDINALITY", Types.BIGINT), col("PAGES", Types.BIGINT), col("FILTER_CONDITION", Types.VARCHAR));
    private static final List<BaseResultSetMetaData.Column> IMPORTED_KEYS = List.of(col("PKTABLE_CAT", Types.VARCHAR), col("PKTABLE_SCHEM", Types.VARCHAR), col("PKTABLE_NAME", Types.VARCHAR), col("PKCOLUMN_NAME", Types.VARCHAR), col("FKTABLE_CAT", Types.VARCHAR), col("FKTABLE_SCHEM", Types.VARCHAR), col("FKTABLE_NAME", Types.VARCHAR), col("FKCOLUMN_NAME", Types.VARCHAR), col("KEY_SEQ", Types.SMALLINT), col("UPDATE_RULE", Types.SMALLINT), col("DELETE_RULE", Types.SMALLINT), col("FK_NAME", Types.VARCHAR), col("PK_NAME", Types.VARCHAR), col("DEFERRABILITY", Types.SMALLINT));
    private static final List<BaseResultSetMetaData.Column> SCHEMAS = List.of(col("TABLE_SCHEM", Types.VARCHAR), col("TABLE_CATALOG", Types.VARCHAR));
    private static final List<BaseResultSetMetaData.Column> CATALOGS = List.of(col("TABLE_CAT", Types.VARCHAR));
    private static final List<BaseResultSetMetaData.Column> TABLE_TYPES = List.of(col("TABLE_TYPE", Types.VARCHAR));
    private static final List<BaseResultSetMetaData.Column> TYPE_INFO = List.of(col("TYPE_NAME", Types.VARCHAR), col("DATA_TYPE", Types.INTEGER), col("PRECISION", Types.INTEGER), col("LITERAL_PREFIX", Types.VARCHAR), col("LITERAL_SUFFIX", Types.VARCHAR), col("CREATE_PARAMS", Types.VARCHAR), col("NULLABLE", Types.SMALLINT), col("CASE_SENSITIVE", Types.BOOLEAN), col("SEARCHABLE", Types.SMALLINT), col("UNSIGNED_ATTRIBUTE", Types.BOOLEAN), col("FIXED_PREC_SCALE", Types.BOOLEAN), col("AUTO_INCREMENT", Types.BOOLEAN), col("LOCAL_TYPE_NAME", Types.VARCHAR), col("MINIMUM_SCALE", Types.SMALLINT), col("MAXIMUM_SCALE", Types.SMALLINT), col("SQL_DATA_TYPE", Types.INTEGER), col("SQL_DATETIME_SUB", Types.INTEGER), col("NUM_PREC_RADIX", Types.INTEGER));
    private static final List<BaseResultSetMetaData.Column> PROCEDURES = List.of(col("PROCEDURE_CAT", Types.VARCHAR), col("PROCEDURE_SCHEM", Types.VARCHAR), col("PROCEDURE_NAME", Types.VARCHAR), col("RESERVED1", Types.VARCHAR), col("RESERVED2", Types.VARCHAR), col("RESERVED3", Types.VARCHAR), col("REMARKS", Types.VARCHAR), col("PROCEDURE_TYPE", Types.SMALLINT), col("SPECIFIC_NAME", Types.VARCHAR));
    private static final List<BaseResultSetMetaData.Column> PROCEDURE_COLUMNS = List.of(col("PROCEDURE_CAT", Types.VARCHAR), col("PROCEDURE_SCHEM", Types.VARCHAR), col("PROCEDURE_NAME", Types.VARCHAR), col("COLUMN_NAME", Types.VARCHAR), col("COLUMN_TYPE", Types.SMALLINT), col("DATA_TYPE", Types.INTEGER), col("TYPE_NAME", Types.VARCHAR), col("PRECISION", Types.INTEGER), col("LENGTH", Types.INTEGER), col("SCALE", Types.SMALLINT), col("RADIX", Types.SMALLINT), col("NULLABLE", Types.SMALLINT), col("REMARKS", Types.VARCHAR), col("COLUMN_DEF", Types.VARCHAR), col("SQL_DATA_TYPE", Types.INTEGER), col("SQL_DATETIME_SUB", Types.INTEGER), col("CHAR_OCTET_LENGTH", Types.INTEGER), col("ORDINAL_POSITION", Types.INTEGER), col("IS_NULLABLE", Types.VARCHAR), col("SPECIFIC_NAME", Types.VARCHAR));
    private static final List<BaseResultSetMetaData.Column> FUNCTIONS = List.of(col("FUNCTION_CAT", Types.VARCHAR), col("FUNCTION_SCHEM", Types.VARCHAR), col("FUNCTION_NAME", Types.VARCHAR), col("REMARKS", Types.VARCHAR), col("FUNCTION_TYPE", Types.SMALLINT), col("SPECIFIC_NAME", Types.VARCHAR));
    private static final List<BaseResultSetMetaData.Column> FUNCTION_COLUMNS = List.of(col("FUNCTION_CAT", Types.VARCHAR), col("FUNCTION_SCHEM", Types.VARCHAR), col("FUNCTION_NAME", Types.VARCHAR), col("COLUMN_NAME", Types.VARCHAR), col("COLUMN_TYPE", Types.SMALLINT), col("DATA_TYPE", Types.INTEGER), col("TYPE_NAME", Types.VARCHAR), col("PRECISION", Types.INTEGER), col("LENGTH", Types.INTEGER), col("SCALE", Types.SMALLINT), col("RADIX", Types.SMALLINT), col("NULLABLE", Types.SMALLINT), col("REMARKS", Types.VARCHAR), col("CHAR_OCTET_LENGTH", Types.INTEGER), col("ORDINAL_POSITION", Types.INTEGER), col("IS_NULLABLE", Types.VARCHAR), col("SPECIFIC_NAME", Types.VARCHAR));
    private static final List<BaseResultSetMetaData.Column> UDTS = List.of(col("TYPE_CAT", Types.VARCHAR), col("TYPE_SCHEM", Types.VARCHAR), col("TYPE_NAME", Types.VARCHAR), col("CLASS_NAME", Types.VARCHAR), col("DATA_TYPE", Types.INTEGER), col("REMARKS", Types.VARCHAR), col("BASE_TYPE", Types.SMALLINT));
}
