# JDBC Metadata Result Sets

This document defines the exact result-set shapes SSHSQLite must return for the metadata methods needed by JDBC tools and the JDBC specification. Column order follows the JDBC `DatabaseMetaData` contract. Unknown values use `null`, empty string, or conservative constants as specified here, but the column must still exist with the correct name and type.

## Shared Rules

SQLite mapping:

- JDBC catalog is `null` for MVP.
- JDBC schema maps to SQLite database name: `main`, `temp`, or an attached database name if attachments are enabled by policy.
- Internal objects named `sqlite_%` are hidden by default unless a future metadata option explicitly includes internal objects.
- Pattern arguments use JDBC SQL pattern semantics: `%` matches any sequence, `_` matches one character, and `getSearchStringEscape()` returns `\`.
- `null` pattern means no filter.
- Empty string pattern matches only empty string.
- Identifier values returned in metadata are unquoted names.
- Metadata queries must use SQLite 3.27-compatible forms: `sqlite_master` for tables/views and schema-qualified `PRAGMA` statements for column, index, foreign-key, and database-list introspection.

SQLite source queries:

- Schemas: `PRAGMA database_list`.
- Tables/views: `sqlite_master` for each allowed schema. Do not require `sqlite_schema`; it is unavailable on SQLite 3.27.2.
- Columns: `PRAGMA schema.table_xinfo(table)` to include hidden columns and generated columns on SQLite versions that support generated columns.
- Indexes: `PRAGMA schema.index_list(table)` and `PRAGMA schema.index_xinfo(index)` where available.
- Foreign keys: `PRAGMA schema.foreign_key_list(table)`.

Table editability:

- Metadata defaults to non-writable unless safe identity is proven.
- Views, virtual tables, FTS tables, generated/hidden columns, readonly connections, and internal `sqlite_%` objects are non-editable.
- Rowid-only GUI editing is unsupported until pinned DataGrip/DBeaver evidence proves safe `_rowid_` handling.
- Production GUI editing requires an explicit `INTEGER PRIMARY KEY` or complete non-null primary-key metadata unless rowid evidence exists.

## `getTables(catalog, schemaPattern, tableNamePattern, types)`

Columns:

| Ord | Column | JDBC type | Value |
| --- | --- | --- | --- |
| 1 | `TABLE_CAT` | `VARCHAR` | `null` |
| 2 | `TABLE_SCHEM` | `VARCHAR` | SQLite schema name |
| 3 | `TABLE_NAME` | `VARCHAR` | table/view name |
| 4 | `TABLE_TYPE` | `VARCHAR` | `TABLE`, `VIEW`, `SYSTEM TABLE` only if internal objects requested |
| 5 | `REMARKS` | `VARCHAR` | `null` |
| 6 | `TYPE_CAT` | `VARCHAR` | `null` |
| 7 | `TYPE_SCHEM` | `VARCHAR` | `null` |
| 8 | `TYPE_NAME` | `VARCHAR` | `null` |
| 9 | `SELF_REFERENCING_COL_NAME` | `VARCHAR` | `null` |
| 10 | `REF_GENERATION` | `VARCHAR` | `null` |

Rows:

- Include `sqlite_master.type IN ('table', 'view')`.
- Exclude `sqlite_%` unless internal objects are requested.
- Map SQLite `table` to `TABLE`, `view` to `VIEW`.
- Apply `types` filter after mapping.

Sort order: `TABLE_TYPE`, `TABLE_CAT`, `TABLE_SCHEM`, `TABLE_NAME`.

## `getColumns(catalog, schemaPattern, tableNamePattern, columnNamePattern)`

Columns:

| Ord | Column | JDBC type | Value |
| --- | --- | --- | --- |
| 1 | `TABLE_CAT` | `VARCHAR` | `null` |
| 2 | `TABLE_SCHEM` | `VARCHAR` | SQLite schema name |
| 3 | `TABLE_NAME` | `VARCHAR` | table/view name |
| 4 | `COLUMN_NAME` | `VARCHAR` | column name |
| 5 | `DATA_TYPE` | `INTEGER` | `java.sql.Types` from declared type affinity |
| 6 | `TYPE_NAME` | `VARCHAR` | declared SQLite type or storage class fallback |
| 7 | `COLUMN_SIZE` | `INTEGER` | declared precision if parsed, otherwise `null` |
| 8 | `BUFFER_LENGTH` | `INTEGER` | `null` |
| 9 | `DECIMAL_DIGITS` | `INTEGER` | declared scale if parsed, otherwise `null` |
| 10 | `NUM_PREC_RADIX` | `INTEGER` | `10` for numeric, otherwise `null` |
| 11 | `NULLABLE` | `INTEGER` | `columnNoNulls`, `columnNullable`, or `columnNullableUnknown` |
| 12 | `REMARKS` | `VARCHAR` | `null` |
| 13 | `COLUMN_DEF` | `VARCHAR` | default expression from `PRAGMA table_xinfo` `dflt_value` |
| 14 | `SQL_DATA_TYPE` | `INTEGER` | `null` |
| 15 | `SQL_DATETIME_SUB` | `INTEGER` | `null` |
| 16 | `CHAR_OCTET_LENGTH` | `INTEGER` | same as `COLUMN_SIZE` for text if known, otherwise `null` |
| 17 | `ORDINAL_POSITION` | `INTEGER` | 1-based column position |
| 18 | `IS_NULLABLE` | `VARCHAR` | `NO`, `YES`, or empty string if unknown |
| 19 | `SCOPE_CATALOG` | `VARCHAR` | `null` |
| 20 | `SCOPE_SCHEMA` | `VARCHAR` | `null` |
| 21 | `SCOPE_TABLE` | `VARCHAR` | `null` |
| 22 | `SOURCE_DATA_TYPE` | `SMALLINT` | `null` |
| 23 | `IS_AUTOINCREMENT` | `VARCHAR` | `YES`, `NO`, or empty string |
| 24 | `IS_GENERATEDCOLUMN` | `VARCHAR` | `YES`, `NO`, or empty string |

Rules:

- Use `PRAGMA schema.table_xinfo(table)` so hidden columns are visible and generated columns are visible on SQLite versions that support generated columns.
- Hidden/generated columns are reported but marked non-writable by `ResultSetMetaData`.
- `INTEGER PRIMARY KEY` rowid aliases map to `IS_AUTOINCREMENT=YES`, matching `ResultSetMetaData.isAutoIncrement()`. Detecting the literal SQLite `AUTOINCREMENT` keyword may be exposed later only as a separate implementation detail, not through this JDBC column.
- Primary-key columns are non-null for metadata purposes even if SQLite reports `notnull=0` for rowid aliases.

Sort order: `TABLE_CAT`, `TABLE_SCHEM`, `TABLE_NAME`, `ORDINAL_POSITION`.

## `getPrimaryKeys(catalog, schema, table)`

Columns:

| Ord | Column | JDBC type | Value |
| --- | --- | --- | --- |
| 1 | `TABLE_CAT` | `VARCHAR` | `null` |
| 2 | `TABLE_SCHEM` | `VARCHAR` | SQLite schema name |
| 3 | `TABLE_NAME` | `VARCHAR` | table name |
| 4 | `COLUMN_NAME` | `VARCHAR` | primary-key column name |
| 5 | `KEY_SEQ` | `SMALLINT` | 1-based key sequence |
| 6 | `PK_NAME` | `VARCHAR` | `null` or detected index/constraint name |

Rules:

- Use `PRAGMA schema.table_xinfo(table)` `pk` values for primary-key ordinal.
- For `INTEGER PRIMARY KEY`, report the declared column, not hidden `_rowid_`.
- Rowid-only tables without an explicit primary key return no rows for MVP.

Sort order: `COLUMN_NAME` is not used; return ordered by `KEY_SEQ`.

## `getIndexInfo(catalog, schema, table, unique, approximate)`

Columns:

| Ord | Column | JDBC type | Value |
| --- | --- | --- | --- |
| 1 | `TABLE_CAT` | `VARCHAR` | `null` |
| 2 | `TABLE_SCHEM` | `VARCHAR` | SQLite schema name |
| 3 | `TABLE_NAME` | `VARCHAR` | table name |
| 4 | `NON_UNIQUE` | `BOOLEAN` | inverse of SQLite unique flag |
| 5 | `INDEX_QUALIFIER` | `VARCHAR` | `null` |
| 6 | `INDEX_NAME` | `VARCHAR` | index name |
| 7 | `TYPE` | `SMALLINT` | `tableIndexOther` |
| 8 | `ORDINAL_POSITION` | `SMALLINT` | 1-based index column position |
| 9 | `COLUMN_NAME` | `VARCHAR` | indexed column name, or `null` for expression indexes |
| 10 | `ASC_OR_DESC` | `VARCHAR` | `A`, `D`, or `null` if unknown |
| 11 | `CARDINALITY` | `BIGINT` | `null` |
| 12 | `PAGES` | `BIGINT` | `null` |
| 13 | `FILTER_CONDITION` | `VARCHAR` | partial-index WHERE clause if available, otherwise `null` |

Rules:

- Use `PRAGMA schema.index_list(table)` and `PRAGMA schema.index_xinfo(index)` where available.
- If `unique=true`, include only unique indexes.
- Do not emit statistic rows for MVP.

Sort order: `NON_UNIQUE`, `TYPE`, `INDEX_NAME`, `ORDINAL_POSITION`.

## `getImportedKeys(catalog, schema, table)`

Columns:

| Ord | Column | JDBC type | Value |
| --- | --- | --- | --- |
| 1 | `PKTABLE_CAT` | `VARCHAR` | `null` |
| 2 | `PKTABLE_SCHEM` | `VARCHAR` | SQLite schema name |
| 3 | `PKTABLE_NAME` | `VARCHAR` | referenced table |
| 4 | `PKCOLUMN_NAME` | `VARCHAR` | referenced column, or `null` if SQLite omits it |
| 5 | `FKTABLE_CAT` | `VARCHAR` | `null` |
| 6 | `FKTABLE_SCHEM` | `VARCHAR` | SQLite schema name |
| 7 | `FKTABLE_NAME` | `VARCHAR` | foreign-key table |
| 8 | `FKCOLUMN_NAME` | `VARCHAR` | foreign-key column |
| 9 | `KEY_SEQ` | `SMALLINT` | SQLite `seq + 1` within FK id |
| 10 | `UPDATE_RULE` | `SMALLINT` | mapped JDBC imported-key rule |
| 11 | `DELETE_RULE` | `SMALLINT` | mapped JDBC imported-key rule |
| 12 | `FK_NAME` | `VARCHAR` | generated stable name such as `fk_<table>_<id>` |
| 13 | `PK_NAME` | `VARCHAR` | `null` |
| 14 | `DEFERRABILITY` | `SMALLINT` | `importedKeyNotDeferrable` unless detected otherwise |

Rules:

- Use `PRAGMA schema.foreign_key_list(table)`.
- Map actions: `CASCADE`, `RESTRICT`, `SET NULL`, `NO ACTION`, `SET DEFAULT` to JDBC constants.

Sort order: `PKTABLE_CAT`, `PKTABLE_SCHEM`, `PKTABLE_NAME`, `KEY_SEQ`.

## `getExportedKeys(catalog, schema, table)`

Shape is identical to `getImportedKeys`.

Rules:

- Iterate allowed tables and return rows whose `PKTABLE_NAME` matches `table`.
- Same action and deferrability mapping as imported keys.

Sort order: `FKTABLE_CAT`, `FKTABLE_SCHEM`, `FKTABLE_NAME`, `KEY_SEQ`.

## `getCrossReference(parentCatalog, parentSchema, parentTable, foreignCatalog, foreignSchema, foreignTable)`

Shape is identical to `getImportedKeys`.

Rules:

- Return exported/imported FK rows constrained by both parent and foreign table arguments.

Sort order: `FKTABLE_CAT`, `FKTABLE_SCHEM`, `FKTABLE_NAME`, `KEY_SEQ`.

## `getSchemas()` And `getSchemas(catalog, schemaPattern)`

Columns:

| Ord | Column | JDBC type | Value |
| --- | --- | --- | --- |
| 1 | `TABLE_SCHEM` | `VARCHAR` | SQLite database name |
| 2 | `TABLE_CATALOG` | `VARCHAR` | `null` |

Rows:

- Include `main` and `temp`.
- Include attached databases only if attachment policy allows them.

Sort order: `TABLE_CATALOG`, `TABLE_SCHEM`.

## `getCatalogs()`

Columns:

| Ord | Column | JDBC type | Value |
| --- | --- | --- | --- |
| 1 | `TABLE_CAT` | `VARCHAR` | no rows for MVP |

SQLite schemas are represented as JDBC schemas, not catalogs.

## `getTableTypes()`

Columns:

| Ord | Column | JDBC type | Value |
| --- | --- | --- | --- |
| 1 | `TABLE_TYPE` | `VARCHAR` | `TABLE`, `VIEW`; `SYSTEM TABLE` only when internal objects are requested |

Sort order: `TABLE_TYPE`.

## `getTypeInfo()`

Columns:

| Ord | Column | JDBC type | Value |
| --- | --- | --- | --- |
| 1 | `TYPE_NAME` | `VARCHAR` | SQLite/JDBC type name |
| 2 | `DATA_TYPE` | `INTEGER` | `java.sql.Types` value |
| 3 | `PRECISION` | `INTEGER` | max precision or `null` |
| 4 | `LITERAL_PREFIX` | `VARCHAR` | quote prefix, usually `'` for text |
| 5 | `LITERAL_SUFFIX` | `VARCHAR` | quote suffix, usually `'` for text |
| 6 | `CREATE_PARAMS` | `VARCHAR` | `null` |
| 7 | `NULLABLE` | `SMALLINT` | `typeNullable` |
| 8 | `CASE_SENSITIVE` | `BOOLEAN` | true for text |
| 9 | `SEARCHABLE` | `SMALLINT` | `typeSearchable` |
| 10 | `UNSIGNED_ATTRIBUTE` | `BOOLEAN` | false for numeric |
| 11 | `FIXED_PREC_SCALE` | `BOOLEAN` | false |
| 12 | `AUTO_INCREMENT` | `BOOLEAN` | true only for integer rowid type row |
| 13 | `LOCAL_TYPE_NAME` | `VARCHAR` | `null` |
| 14 | `MINIMUM_SCALE` | `SMALLINT` | `0` or `null` |
| 15 | `MAXIMUM_SCALE` | `SMALLINT` | `null` |
| 16 | `SQL_DATA_TYPE` | `INTEGER` | `null` |
| 17 | `SQL_DATETIME_SUB` | `INTEGER` | `null` |
| 18 | `NUM_PREC_RADIX` | `INTEGER` | `10` for numeric, otherwise `null` |

Minimum rows:

- `INTEGER` -> `Types.BIGINT`.
- `REAL` -> `Types.DOUBLE`.
- `TEXT` -> `Types.VARCHAR`.
- `BLOB` -> `Types.BLOB`.
- `NUMERIC` -> `Types.NUMERIC`.
- `NULL` -> `Types.NULL`.

Additional rows should include common declared type spellings desktop tools expect to offer in create/edit dialogs, such as `VARCHAR`, `CHAR`, `BOOLEAN`, `DATE`, `DATETIME`, `TIMESTAMP`, `DECIMAL`, `JSON`, and related aliases. These names are advisory: SQLite accepts arbitrary declared type names and applies affinity rules.

Existing table columns must expose the original declared type from `PRAGMA table_xinfo.type` through `getColumns().TYPE_NAME`, while `getColumns().DATA_TYPE` remains the closest JDBC type from SQLite affinity rules.

## Empty Metadata Result Sets

These methods return no rows in MVP but must return correctly shaped result sets:

- `getProcedures`.
- `getProcedureColumns`.
- `getFunctions`.
- `getFunctionColumns`.
- `getUDTs`.
- `getSuperTypes`.
- `getSuperTables`.
- `getAttributes`.
- `getColumnPrivileges`.
- `getTablePrivileges`.
- `getBestRowIdentifier` unless explicit row identity support is implemented.
- `getVersionColumns`.
- `getPseudoColumns` unless `_rowid_` pseudo-column support is explicitly implemented and tool-tested.
- `getClientInfoProperties` unless client info properties are advertised.

Each empty result set must still expose JDBC-specified columns in JDBC-specified order. Tests must validate column names, order, and JDBC types.

## Type Affinity Mapping

Declared SQLite type to JDBC type:

| SQLite declared type/affinity | JDBC type |
| --- | --- |
| contains `INT` | `Types.BIGINT` or `Types.INTEGER` if width is known small |
| contains `CHAR`, `CLOB`, `TEXT` | `Types.VARCHAR` |
| contains `BLOB` or empty declared type | `Types.BLOB` |
| contains `REAL`, `FLOA`, `DOUB` | `Types.DOUBLE` |
| contains `NUMERIC`, `DECIMAL`, `BOOLEAN`, `DATE`, `DATETIME` | `Types.NUMERIC` or documented configured mapping |

Runtime `ResultSet.getObject()` still follows SQLite storage class, not only declared type.
