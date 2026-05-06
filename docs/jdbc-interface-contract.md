# JDBC Interface Contract

## Status

The JDBC implementation interface is not complete until every public method inherited from the targeted `java.sql` interfaces has an explicit documented behavior and test coverage. The MVP SQL feature set can be small, but the Java interface surface must be exhaustive enough that tools never hit `AbstractMethodError`, `NoSuchMethodError`, or accidental `UnsupportedOperationException`.

## Java And JDBC Baseline

Initial production baseline:

```text
Java runtime: Java 11+
Compile target: --release 11
JDBC API: Java 11 java.sql interfaces
```

Java 11 compatibility is required for desktop tools that still run on Java 11. The driver source must avoid post-Java-11 language and library APIs, and the JDBC interface coverage tests must pass for the Java 11 target.

## Implementation Strategy

Do not implement large JDBC interfaces directly in transport classes.

Required base adapters:

- `BaseDriver implements java.sql.Driver`.
- `BaseConnection implements java.sql.Connection`.
- `BaseStatement implements java.sql.Statement`.
- `BasePreparedStatement extends BaseStatement implements java.sql.PreparedStatement`.
- `BaseResultSet implements java.sql.ResultSet`.
- `BaseResultSetMetaData implements java.sql.ResultSetMetaData`.
- `BaseDatabaseMetaData implements java.sql.DatabaseMetaData`.
- `BaseParameterMetaData implements java.sql.ParameterMetaData` if `getParameterMetaData()` returns an object.

Adapter rules:

- Every method in the target Java `java.sql` interface must be implemented in the adapter layer.
- Concrete SSHSQLite classes override only methods with real supported behavior.
- Default unsupported feature behavior is `SQLFeatureNotSupportedException` when the signature allows `SQLException`.
- Invalid state, closed object, invalid argument, protocol failure, and remote failure use `SQLException` or a more specific documented subclass.
- Public JDBC methods must never throw `AbstractMethodError`, `NoSuchMethodError`, or `UnsupportedOperationException`.
- Safe conservative metadata capability methods should return constants instead of throwing when JDBC tools reasonably expect answers.
- No method may silently no-op unless JDBC permits advisory behavior and this document names that method.

## Support Matrix Requirement

Before implementation is considered complete, maintain method support tables for these interfaces:

- `Driver`.
- `Connection`.
- `Statement`.
- `PreparedStatement`.
- `ResultSet`.
- `ResultSetMetaData`.
- `DatabaseMetaData`.
- `ParameterMetaData` if exposed.
- `Wrapper` behavior for every JDBC object.

Each method row must include:

- Method name or overload group.
- Status from the canonical generated vocabulary in `docs/jdbc-method-support.md`, such as `supported`, `conservative-constant`, `local-advisory-state`, `empty-metadata-result`, `populated-metadata-result`, `supported-in-prepared-milestone`, `unsupported-feature`, `invalid-state-error`, `closed-object-error`, or `not-exposed`.
- Exception type and SQLState for failures.
- Implementation phase.
- Test coverage.

Unsupported method policy:

- Optional JDBC feature not implemented: `SQLFeatureNotSupportedException`, SQLState `0A000`.
- Method called on closed object: `SQLException`, SQLState `08003` for closed connection or `HY010` for invalid object state.
- Invalid argument or invalid parameter index: `SQLException`, SQLState `HY000` or a more specific documented state.
- Invalid cursor position: `SQLException`, SQLState `HY010`.
- Broken SSH/helper transport: `SQLNonTransientConnectionException`, SQLState `08006`.
- Timeout: `SQLTimeoutException`, SQLState `HYT00`.
- Client info failure: `SQLClientInfoException`.

## Wrapper Contract

Every JDBC object implements `java.sql.Wrapper` consistently.

- `isWrapperFor(iface)` returns `true` when `iface.isInstance(this)`.
- `unwrap(iface)` returns `this` cast to `iface` when assignable.
- Unsupported unwrap targets throw `SQLException` with SQLState `HY000`.
- Wrapper methods remain callable after close unless the specific class has a stronger reason to reject them.

## Closed Object Behavior

Close methods are idempotent.

| Object | Methods allowed after close | Other methods |
| --- | --- | --- |
| `Connection` | `close()`, `isClosed()`, `isValid()`, wrapper methods | Throw `SQLException` SQLState `08003` |
| `Statement` | `close()`, `isClosed()`, wrapper methods | Throw `SQLException` SQLState `HY010` |
| `PreparedStatement` | Same as `Statement` | Same as `Statement` |
| `ResultSet` | `close()`, `isClosed()`, wrapper methods | Throw `SQLException` SQLState `HY010` |
| `ResultSetMetaData` | Metadata remains usable if already materialized | Throw only if metadata was never materialized |
| `DatabaseMetaData` | Wrapper methods may remain usable | Methods requiring live connection throw `SQLException` SQLState `08003` |

Closing a `Connection` closes all child statements and result sets. Closing a `Statement` closes its result set. Closing a streaming `ResultSet` must send `closeCursor` or otherwise finalize the remote SQLite statement promptly.

## Driver Contract

Required behavior:

- Register through Java service loading and optionally through static driver registration.
- `connect(url, props)` returns `null` for non-`jdbc:sshsqlite:` URLs.
- `acceptsURL(url)` returns `false` for `null` and non-matching URLs.
- URL/property validation failures for matching URLs throw `SQLException` from `connect()`.
- `getPropertyInfo()` returns known connection properties with defaults and required flags, excluding secret values.
- `getMajorVersion()` and `getMinorVersion()` reflect the Java driver version.
- `jdbcCompliant()` returns `false`.
- `getParentLogger()` returns a real logger or throws `SQLFeatureNotSupportedException` if not wired.

## Connection Contract

State defaults:

| State | Default | Behavior |
| --- | --- | --- |
| `autoCommit` | `true` | JDBC transaction rules in `jdbc-sqlite-semantics.md` |
| `readOnly` | Open option | Physical capability; read-only opens cannot become write-capable |
| `catalog` | `null` | SQLite has no JDBC catalog in MVP |
| `schema` | `main` | Maps to SQLite database name |
| `transactionIsolation` | `TRANSACTION_SERIALIZABLE` or documented SQLite equivalent | Other levels rejected unless explicitly supported |
| `holdability` | `CLOSE_CURSORS_AT_COMMIT` | `HOLD_CURSORS_OVER_COMMIT` unsupported |
| `networkTimeout` | `0` or configured transport timeout | See operations timeout properties |
| `clientInfo` | empty | Stored locally or rejected with `SQLClientInfoException` |
| `warnings` | none | `getWarnings()` returns `null` until warnings are implemented |
| `typeMap` | empty | Non-empty maps unsupported |

Required methods commonly called by tools:

- `getMetaData()`.
- `isValid(timeout)` using protocol `ping` when no request is active.
- `isReadOnly()` and `setReadOnly(boolean)`.
- `getCatalog()` and `setCatalog(String)`.
- `getSchema()` and `setSchema(String)`.
- `getTransactionIsolation()` and `setTransactionIsolation(int)`.
- `getHoldability()` and `setHoldability(int)`.
- `getWarnings()` and `clearWarnings()`.
- `nativeSQL(sql)`, returning SQL unchanged unless preprocessing is explicitly requested elsewhere.
- `setClientInfo()` and `getClientInfo()`.
- `setNetworkTimeout(executor, ms)` and `getNetworkTimeout()`.
- `abort(executor)`, which breaks the connection and closes the helper using the provided executor where practical.
- `abort()` is connection-fatal. Transaction outcome is unknown unless a rollback acknowledgement was received before abort, and pooled physical connections must always be evicted.

Readonly rules:

- `readonly` from connection open is immutable for the physical helper connection.
- `setReadOnly(false)` on a physically readonly connection throws `SQLException`.
- `setReadOnly(true)` on a read-write connection must either enable enforced logical readonly through helper authorizer/`PRAGMA query_only=ON` for that logical connection, or throw `SQLException` explaining that readonly must be chosen at connect time.
- `isReadOnly()` reports effective enforcement, not advisory intent.
- Changing read-only state during an active transaction throws `SQLException`.

Schema/catalog rules:

- SQLite database names such as `main`, `temp`, and authorized attached names map to JDBC schema.
- Catalog is `null` in MVP.
- `setSchema("main")` and `setSchema("temp")` are accepted when available.
- Unknown schemas throw `SQLException` or are rejected by metadata calls according to the support table.
- Metadata catalog parameters are ignored only where JDBC permits and docs state it.

## Statement Contract

Required behavior:

- `execute()` and `executeQuery()` maintain JDBC result state for production read-only MVP. `executeUpdate()` and `executeLargeUpdate()` throw `SQLFeatureNotSupportedException` in production read-only MVP and maintain JDBC update-count state only in the write-capable release.
- `getResultSet()` returns the current result set or `null`.
- `getUpdateCount()` returns current update count or `-1`.
- `getLargeUpdateCount()` mirrors update count as `long`.
- `getMoreResults()` returns `false` for MVP and clears current result state according to JDBC rules.
- Generated-key overloads accepting `RETURN_GENERATED_KEYS` or `NO_GENERATED_KEYS` execute writes normally. `getGeneratedKeys()` returns a one-column result set containing SQLite `last_insert_rowid()` when generated keys were requested and the helper returned it; column-index and column-name overloads remain `SQLFeatureNotSupportedException`.
- Batch methods throw `SQLFeatureNotSupportedException` until implemented. `executeBatch()` must never partially execute in MVP.
- `closeOnCompletion()` and `isCloseOnCompletion()` maintain local state and close the statement after result exhaustion when enabled.
- `setPoolable()` and `isPoolable()` are advisory local state unless disabled by support table.

Configuration methods:

- `setMaxRows(0)` means no user max, but driver safety caps still apply.
- Negative `setMaxRows()` values throw `SQLException`.
- `setLargeMaxRows()` follows the same rule and must be capped to driver/protocol limits.
- `setFetchSize(0)` means driver default. Negative values throw `SQLException`.
- `setFetchDirection(FETCH_FORWARD)` is accepted. Other directions throw `SQLFeatureNotSupportedException`.
- `setEscapeProcessing(boolean)` records advisory local state only; JDBC escape syntax rewriting is not implemented unless a future compatibility feature documents and tests it.
- `setQueryTimeout(seconds)` uses JDBC seconds; negative values throw `SQLException`.
- `setMaxFieldSize(0)` means no user field cap subject to protocol limits. Positive values cap returned field bytes where practical.

Warning methods return `null` and clear no state until warning production is implemented.

## PreparedStatement Contract

PreparedStatement is a post-read-only milestone. For production read-only MVP, `Connection.prepareStatement(...)` may throw `SQLFeatureNotSupportedException` if PreparedStatement support is not implemented yet. Once the PreparedStatement milestone starts, this contract applies.

Standard JDBC setters bind by SQLite parameter index for all placeholder forms: `?`, `?NNN`, `:name`, `@name`, and `$name`.

Sparse `?NNN` placeholder indexes are rejected for MVP unless explicitly added to the support matrix and tested. The driver/helper must never rewrite SQL to normalize parameters.

Required behavior:

- Inherited SQL-string execution methods on `PreparedStatement`, such as `executeQuery(String)`, throw `SQLException` because prepared SQL is fixed.
- `clearParameters()` clears all bound values.
- Missing required parameters fail before execution.
- Invalid parameter indexes throw `SQLException`.
- Parameter values persist across executions until changed or cleared, following JDBC behavior.
- Failed execution does not clear parameters.
- `getMetaData()` may prepare remotely to discover result columns or return `null` if not known before execution.
- `getParameterMetaData()` returns conservative metadata or throws `SQLFeatureNotSupportedException`; it must not return misleading parameter types.

Setter coverage for the PreparedStatement milestone:

- `setNull()`.
- `setString()`.
- `setInt()`, `setLong()`, `setDouble()`, `setFloat()`.
- `setBigDecimal()` using documented text or numeric conversion.
- `setBoolean()`.
- `setBytes()`.
- `setDate()`, `setTime()`, `setTimestamp()` plus calendar overloads if date/time support is advertised.
- `setObject(index, value)` and `setObject(index, value, targetSqlType)` for documented Java types.

Stream setters, `setBlob()`, `setClob()`, `setArray()`, `setSQLXML()`, `setRowId()`, and unsupported JDBC object setters throw `SQLFeatureNotSupportedException` until implemented.

## ResultSet Contract

Cursor states:

- Initial state is before first row.
- `next()` advances and returns `true` on a row, `false` after exhaustion.
- Getters before the first row, after exhaustion, or after close throw `SQLException`.
- `wasNull()` before any getter throws `SQLException` or returns `false` only if the support table explicitly chooses that behavior.
- Forward-only navigation is supported. Other navigation methods throw `SQLFeatureNotSupportedException`.

Column access:

- Column indexes are 1-based.
- Out-of-range indexes throw `SQLException`.
- `findColumn(label)` uses JDBC column labels, then names, with deterministic duplicate handling: first matching column wins.
- Label matching should be case-insensitive for tool compatibility unless this causes ambiguity; ambiguous duplicates should prefer the first column.
- Getter conversions follow the type mapping in `jdbc-sqlite-semantics.md`.
- `getObject(index, Class<T>)` and `getObject(label, Class<T>)` support common Java target classes or throw `SQLFeatureNotSupportedException` for unsupported conversions.

Required methods:

- `getMetaData()`.
- `getStatement()`.
- `getType()` returns `TYPE_FORWARD_ONLY`.
- `getConcurrency()` returns `CONCUR_READ_ONLY`.
- `getHoldability()` returns `CLOSE_CURSORS_AT_COMMIT`.
- `isBeforeFirst()`, `isAfterLast()`, `isFirst()`, and `isLast()` return best-effort values for forward-only result sets without forcing full buffering.
- Update methods throw `SQLFeatureNotSupportedException`.
- `getBlob()` either returns a simple read-only `Blob` backed by `byte[]` or throws `SQLFeatureNotSupportedException`; choose before implementation and test it.

## ResultSetMetaData Contract

Required methods:

- `getColumnCount()`.
- `getColumnName(column)` and `getColumnLabel(column)`.
- `getTableName(column)`, `getSchemaName(column)`, and `getCatalogName(column)` where known, otherwise empty strings.
- `getColumnType(column)` using `java.sql.Types` mapped from SQLite declared type affinity where possible.
- `getColumnTypeName(column)` using declared SQLite type or runtime storage class fallback.
- `getColumnClassName(column)` from Java type mapping.
- `getPrecision(column)`, `getScale(column)`, and `getColumnDisplaySize(column)` with conservative values when unknown.
- `isNullable(column)` using SQLite metadata where available, otherwise `columnNullableUnknown`.
- `isAutoIncrement(column)` true for SQLite integer primary-key rowid aliases where detectable.
- `isSigned(column)` true for integer/real numeric types.
- `isReadOnly(column)`, `isWritable(column)`, and `isDefinitelyWritable(column)` conservative and table-edit aware.
- `isSearchable(column)`, `isCurrency(column)`, and `isCaseSensitive(column)` conservative constants.

## DatabaseMetaData Contract

Database tools need conservative answers more than exceptions.

Minimum metadata result-set methods:

- `getTables(catalog, schemaPattern, tableNamePattern, types)`.
- `getColumns(catalog, schemaPattern, tableNamePattern, columnNamePattern)`.
- `getPrimaryKeys(catalog, schema, table)`.
- `getIndexInfo(catalog, schema, table, unique, approximate)`.
- `getImportedKeys(catalog, schema, table)`.
- `getExportedKeys(catalog, schema, table)`.
- `getCrossReference(...)`.
- `getSchemas()` and `getSchemas(catalog, schemaPattern)`.
- `getCatalogs()`.
- `getTableTypes()`.
- `getTypeInfo()`.
- `getProcedures()`, `getProcedureColumns()`, `getFunctions()`, `getFunctionColumns()`, and `getUDTs()` as empty result sets with correct columns.

Capability methods should return conservative constants:

- `supportsTransactions()` false for the production read-only MVP because manual transaction methods are not exposed; true only in the write-capable release that implements `setAutoCommit`, `commit`, and `rollback`.
- `supportsResultSetType(TYPE_FORWARD_ONLY)` true; other types false.
- `supportsResultSetConcurrency(TYPE_FORWARD_ONLY, CONCUR_READ_ONLY)` true; writable concurrency false.
- `supportsMultipleResultSets()` false.
- `supportsMultipleOpenResults()` false for MVP.
- `supportsBatchUpdates()` false.
- `supportsGetGeneratedKeys()` false for MVP.
- `supportsSavepoints()` false until implemented.
- `supportsNamedParameters()` false for standard JDBC unless a named-parameter extension is exposed outside JDBC.
- `supportsStoredProcedures()` false.
- `getIdentifierQuoteString()` returns `"`.
- `getSearchStringEscape()` returns `\` unless another escape is implemented.

Exact result-set column layouts for metadata methods must match the JDBC specification even when values are `null`, empty, unknown, or conservative. This is required for DataGrip and DBeaver compatibility and is tracked in `docs/jdbc-metadata-resultsets.md`.

Table editing metadata:

- Expose primary keys from `PRAGMA table_xinfo`, `PRAGMA index_list`, and `PRAGMA index_info` where possible.
- Detect `INTEGER PRIMARY KEY` rowid aliases and autoincrement behavior.
- Default result and table metadata to non-writable unless safe edit identity is proven.
- Mark editable only for tables with an explicit `INTEGER PRIMARY KEY` rowid alias or complete non-null primary-key metadata. Rowid-only editing remains unsupported unless pinned DataGrip/DBeaver evidence proves safe exposed `_rowid_` identity handling.
- Treat views, virtual tables, FTS tables, generated/hidden columns, readonly connections, and `WITHOUT ROWID` tables without complete primary-key metadata conservatively as non-editable.
- Internal `sqlite_%` tables are hidden by default unless metadata options request internal objects.
- Rowid identity may be used by tool-generated edit SQL only when table metadata and pinned desktop-tool evidence prove it is safe.
- Generated edit SQL must quote identifiers, include original key values in the predicate, require update count exactly `1`, and fail or rollback when update count is `0` or greater than `1`.

## Interface Exhaustiveness Tests

Required tests:

- Reflection test over every SSHSQLite class implementing a `java.sql` interface to ensure every public interface method is implemented.
- Invocation smoke test for every zero-argument and safe one-argument method on open objects.
- Closed-object invocation test for every method to verify documented closed-state behavior.
- Unsupported feature test asserting `SQLFeatureNotSupportedException`, not `UnsupportedOperationException`.
- Java baseline test on the supported runtime and compile target.
- Tool trace test capturing unexpected JDBC method calls from DataGrip and DBeaver and updating support tables when needed.
- Generated method-support matrix freshness test for `docs/jdbc-method-support.generated.md`.

The implementation is not production-ready until these tests pass, `docs/jdbc-method-support.generated.md` exists, and the support tables match observed tool behavior.
