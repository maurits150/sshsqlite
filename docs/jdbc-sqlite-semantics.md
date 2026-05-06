# JDBC And SQLite Semantics

## JDBC Scope

Current CLI-over-SSH support:

| Area | Methods and behavior |
| --- | --- |
| Driver | `Driver.connect()`, `acceptsURL()`, basic property metadata |
| Connection | `createStatement()`, `prepareStatement()`, `getAutoCommit()`, `setAutoCommit()`, `commit()`, `rollback()`, `close()`, `isClosed()`, `isValid()`, `getMetaData()`, readonly/schema/catalog methods needed by tools |
| Statement | `executeQuery(sql)`, `execute(sql)`, `executeUpdate(sql)`, `executeLargeUpdate(sql)`, generated-key variants for `RETURN_GENERATED_KEYS`, `close()`, `getResultSet()`, `getUpdateCount()`, `setMaxRows()`, `setFetchSize()`, connection-fatal `setQueryTimeout()` behavior in CLI mode |
| PreparedStatement | Client-side parameter collection, safe SQLite SQL-literal rendering in CLI mode, positional setters, `clearParameters()`, execute methods, generated keys for `RETURN_GENERATED_KEYS` |
| ResultSet | Forward-only `next()`, typed getters, `getObject()`, `getBytes()`, `wasNull()`, close |
| Metadata | Basic `ResultSetMetaData` and enough `DatabaseMetaData` for DBeaver-style browsing |

Unsupported JDBC features must throw `SQLFeatureNotSupportedException` or a documented `SQLException`. They must not silently no-op when JDBC requires visible behavior.

The exhaustive method-level contract lives in [JDBC Interface Contract](jdbc-interface-contract.md). This file describes SQLite/JDBC semantics; the interface contract file controls method coverage and implementation completeness.

Deferred features:

- Multiple active result sets per connection.
- Full JDBC metadata coverage.
- Savepoints.
- Batch prepared statements.
- BLOB streaming through `InputStream`.
- Advanced generated-keys behavior beyond SQLite `last_insert_rowid()`.
- Plain `Driver.connect()` pooling; pooling is only opt-in through `DataSource`.
- Updatable or scrollable result sets.
- Distributed transactions.

## JDBC Compatibility Contract

Desktop tools call many capability and metadata methods before showing tables. The driver should implement conservative, stable responses for:

- `DatabaseMetaData.getTables()`.
- `DatabaseMetaData.getColumns()`.
- `DatabaseMetaData.getPrimaryKeys()`.
- `DatabaseMetaData.getIndexInfo()`.
- `DatabaseMetaData.getImportedKeys()`.
- `DatabaseMetaData.getTypeInfo()`.
- `DatabaseMetaData.getSchemas()`.
- `DatabaseMetaData.getCatalogs()`.
- `DatabaseMetaData.getTableTypes()`.
- `DatabaseMetaData.getIdentifierQuoteString()`.
- `DatabaseMetaData.getSQLKeywords()`.
- Transaction support methods.
- Result-set capability methods.
- Identifier casing methods.
- `Connection.getSchema()` and `setSchema()` behavior.
- `Statement.getResultSet()`, `getUpdateCount()`, and `getMoreResults()`.

Conservative capability rules:

- Result sets are `TYPE_FORWARD_ONLY` and `CONCUR_READ_ONLY`.
- Batch updates are unsupported until implemented.
- Transactions are reported supported only when the connection was opened with `readonly=false`; readonly connections reject manual transaction entry because writes are disallowed.
- Multiple open results are unsupported.
- Basic generated keys are supported for `RETURN_GENERATED_KEYS` statement/prepared-statement write execution by returning SQLite `last_insert_rowid()` as a one-column result set. Column-name and column-index generated-key selection remains unsupported.

Minimum metadata identity methods:

| Method | Current return |
| --- | --- |
| `getDatabaseProductName()` | `SQLite` |
| `getDatabaseProductVersion()` | Remote SQLite version from the sqlite3 CLI/backend |
| `getDriverName()` | `SSHSQLite JDBC Driver` |
| `getDriverVersion()` | Java driver version |
| `getURL()` | Redacted JDBC URL |
| `getUserName()` | SSH username, redacted if configured |
| `supportsMultipleResultSets()` | `false` |
| `supportsBatchUpdates()` | `false` |
| `supportsGetGeneratedKeys()` | `true` for basic `last_insert_rowid()` support |
| `supportsResultSetType(TYPE_FORWARD_ONLY)` | `true` |
| `supportsResultSetConcurrency(TYPE_FORWARD_ONLY, CONCUR_READ_ONLY)` | `true` |

Data-grid edit support must default to non-writable metadata unless safe row identity is proven. Mark editable only for tables with an explicit `INTEGER PRIMARY KEY` rowid alias or complete non-null primary-key metadata. Rowid-only editing remains unsupported unless pinned desktop-tool evidence proves safe exposed `_rowid_` identity handling. Views, virtual tables, FTS tables, generated/hidden columns, `WITHOUT ROWID` tables without full primary-key metadata, internal `sqlite_%` tables, and readonly connections are non-editable by default.

## Pooling Semantics

The built-in pool is an opt-in `DataSource` feature, not hidden behavior inside plain `Driver.connect()`.

Rules:

- `Connection.close()` on a pooled logical connection returns it to the pool after cleanup; physical close happens on eviction or pool shutdown.
- The pool defaults to at most 5 physical connections when enabled.
- Pools are keyed by the full security context and immutable open options, including SSH identity, host-key policy, `sqlite3.path`, database path, readonly mode, output mode, protocol version, and capabilities. Optional helper mode additionally keys on helper path/hash/version when used.
- A physical read-write connection must never satisfy a readonly logical connection.
- Returning to pool closes all statements and result sets, rolls back active transactions, verifies no active transaction remains, then resets `autoCommit`, schema/catalog, warnings, client info, network timeout, effective logical readonly state, and connection-level defaults.
- Pool return must never call `setAutoCommit(true)` before rollback because JDBC `setAutoCommit(true)` can commit active borrower work.
- Statement-level state such as max rows, fetch size, and query timeout belongs to new statement defaults and must not leak across logical borrowers.
- If rollback, reset, validation, or ping fails, the physical connection is closed and evicted.
- Pooled `.param` maps must be per-statement or cleared on return.

## Statement Semantics

`Statement.execute(sql)` must follow JDBC result-state rules.

- Return `true` if the first result is a `ResultSet`.
- Return `false` if the first result is an update count or no result.
- `getResultSet()` returns the current result set or `null`.
- `getUpdateCount()` returns the current update count or `-1`.
- `getMoreResults()` returns `false`; multi-result JDBC traversal is not implemented.
- `executeQuery()` must reject SQL that does not return rows.
- `executeUpdate()` must reject SQL that returns rows.
- Multi-statement SQL scripts are valid for non-row execution paths and are sent to `sqlite3` stdin. The update count is the backend's reported `changes()` value after the script completes, which is normally the last mutating statement rather than an aggregate script count.
- `executeQuery()` is for SQL whose first result is tabular. Scripts that produce multiple tabular outputs are not exposed as multiple JDBC result sets and should fail clearly rather than be partially interpreted.
- Executing a new statement closes the prior active result set on the same connection.

Statement close behavior:

- Closing a statement closes its current result set.
- Closing a streaming result set must finalize the remote SQLite statement promptly through `closeCursor` or an equivalent cleanup path.
- Closing a result set does not close the statement unless JDBC ownership rules require it for a specific method.
- Closing a connection closes all statements and result sets created by it.

## PreparedStatement Semantics

The CLI backend implements `PreparedStatement` through client-side parameter collection and safe SQLite SQL-literal rendering before sending SQL to `sqlite3` stdin. Optional helper/direct-SQLite backends may implement true SQLite prepare/bind/step/finalize semantics instead.

Requirements:

- JDBC parameter indexes are 1-based.
- JDBC setters bind by SQLite parameter index for every placeholder form, including `?`, `?NNN`, `:name`, `@name`, and `$name`.
- Sparse `?NNN` placeholder indexes are rejected unless explicitly added to the support matrix and tested. The driver/helper must never rewrite SQL to normalize parameters.
- Throw `SQLException` if a required positional parameter is unset.
- Support `clearParameters()`.
- For CLI mode, render values as SQLite SQL literals only; never shell-quote parameter values and never include them in the SSH command line.
- For helper/direct-SQLite mode, bind values through SQLite parameters and do not interpolate parameter values into SQL strings.
- Re-render/re-execute on each CLI execution unless server-side prepared statement IDs are later added for a native helper backend.
- SQLite named placeholders such as `:limit`, `@name`, and `$name` may also be supported through a named-binding driver extension, but standard JDBC setters remain index-based.
- Repeated named placeholders share one SQLite parameter index and therefore one bound value.

CLI literal rendering supports the documented setters as follows:

| Setter/value | SQLite literal |
| --- | --- |
| `setNull()` and null setter inputs | `NULL` |
| Strings, dates, times, timestamps, `BigDecimal` | Single-quoted text with embedded `'` doubled |
| Integer types and booleans | Decimal integer; booleans are `1` or `0` |
| Finite float/double | Decimal real literal |
| `byte[]` | `X'...'` hex BLOB literal |

CLI mode supports `?`, `?NNN`, `:name`, `@name`, and `$name` placeholder spelling through index-based JDBC setters. Sparse `?NNN` indexes are rejected. Repeated named placeholders reuse the same bound value. Literal rendering is intentionally less capable than native SQLite binding, but it must be tested for escaping and must not expose shell injection because rendered SQL is written only to stdin.

Common setters to support:

- `setNull()`.
- `setString()`.
- `setInt()`.
- `setLong()`.
- `setDouble()`.
- `setBoolean()`.
- `setBytes()`.
- `setDate()`.
- `setTime()`.
- `setTimestamp()`.
- `setObject()` for documented Java types.

`setObject()` should accept at least `String`, `Integer`, `Long`, `Double`, `Float`, `Boolean`, `byte[]`, `BigDecimal` as text or double by policy, `java.sql.Date`, `java.sql.Time`, and `java.sql.Timestamp` according to the documented date/time strategy.

## Limited `.param` Preprocessor

SQLite dot commands such as `.param`, `.tables`, `.schema`, `.mode`, `.read`, and `.dump` are shell meta-commands, not SQL. They must not be sent to `sqlite3_prepare()` as part of a normal SQL statement.

The driver can provide an optional limited `.param` preprocessor for users who paste simple SQLite shell snippets into a desktop tool:

```sql
.param init
.param set :limit "50"
.param set :offset "0"
SELECT _rowid_ AS rowid, * FROM Track LIMIT :limit OFFSET :offset;
```

Required behavior if this mode is implemented:

- It must be disabled by default.
- It only recognizes `.param` or `.parameter` lines before a single SQL statement.
- It strips recognized dot-command lines before sending SQL to the backend.
- It stores parameters in a per-statement map by default.
- It sends values as bound parameters or backend-native named parameters, not by rewriting SQL text.
- It may support `.param init`, `.param clear`, `.param unset NAME`, and `.param set NAME VALUE`.
- It must reject every other leading `.` command unless that exact command is explicitly implemented.
- It must reject multiline or continued dot commands.
- It must never evaluate `.param set` values as SQL expressions.
- Values must be parsed predictably. Numeric-looking values may become integers/reals, quoted values remain text, and `NULL` maps to SQL null.
- Parameter names must be validated against SQLite prepared-statement parameter metadata before execution.
- Logs must redact `.param set` values by default.

This is not full SQLite CLI compatibility. Exact behavior must be tested against documented accepted cases, not assumed from the `sqlite3` shell implementation.

Safer JDBC-native usage should remain the primary path:

```sql
SELECT _rowid_ AS rowid, * FROM Track LIMIT ? OFFSET ?;
```

Then bind `limit` and `offset` through `PreparedStatement` setters.

## Type Mapping

SQLite has dynamic typing. Declared column type and runtime storage class can differ.

Runtime storage mapping:

| SQLite storage class | Java `getObject()` default | Notes |
| --- | --- | --- |
| `NULL` | `null` | Primitive getters must set `wasNull()` |
| `INTEGER` | `Long` | Narrow getters convert with JDBC semantics |
| `REAL` | `Double` | Reject NaN/infinity from protocol |
| `TEXT` | `String` | UTF-8 JSON text |
| `BLOB` | `byte[]` where the backend supplies typed BLOB values | Streaming deferred; current CLI CSV output cannot reliably distinguish arbitrary BLOB cells from text in all result shapes |

Getter requirements:

- `wasNull()` must be implemented for all getters.
- `getInt()`, `getLong()`, and other primitive getters return JDBC default primitive values for SQL `NULL` and set `wasNull()`.
- `getBoolean()` maps numeric zero to `false` and nonzero to `true`; text boolean mapping should be explicit if supported.
- `getBytes()` returns `byte[]` for typed BLOB values. For current CLI result values that arrive as text, `getBytes()` returns the UTF-8 bytes of that text.
- `ResultSetMetaData.getColumnType()` should infer from declared type affinity when available.
- Runtime values control `getObject()` more than declared types.
- `DatabaseMetaData.getColumns().TYPE_NAME` must preserve SQLite's declared type string from `PRAGMA table_xinfo.type`, including names such as `VARCHAR(255)`, `DATETIME`, `BOOLEAN`, `DECIMAL(10,2)`, and `JSON`.
- `DatabaseMetaData.getTypeInfo()` should include common declared type spellings used by desktop tools, not only SQLite's storage classes/affinities. The list is advisory because SQLite accepts arbitrary type names and applies affinity rules.

Date/time policy:

- SQLite has no native date/time type.
- The current CLI backend stores `Date`, `Time`, and `Timestamp` as ISO-8601 text unless configured otherwise.
- Integer epoch and Julian-day real mappings can be added later only with explicit configuration.
- Metadata should not claim native date/time support without a clear mapping.

## Transactions

The default connection mode is read/write (`readonly=false`). In read/write mode, manual JDBC transactions are exposed and `DatabaseMetaData.supportsTransactions()` returns `true`. In readonly mode, `getAutoCommit()` still returns the current JDBC autocommit flag, but entering manual transaction mode is rejected because the current implementation treats `setAutoCommit(false)` as write-capable transaction control and `DatabaseMetaData.supportsTransactions()` returns `false`.

Default JDBC behavior:

- `autoCommit=true` is the default.
- In `autoCommit=true`, each statement commits when it completes successfully, following SQLite's normal autocommit behavior.
- For streaming queries, statement completion means the result is exhausted or the `ResultSet` is closed and the SQLite statement is finalized.
- `commit()` and `rollback()` throw `SQLException` when `autoCommit=true`.
- In `autoCommit=false`, transactions start lazily before the first statement that needs one.
- Default transaction mode is deferred (`BEGIN DEFERRED` in CLI mode).
- `BEGIN IMMEDIATE` is available through `transactionMode=immediate`.
- `BEGIN EXCLUSIVE` is available through explicit `transactionMode=exclusive` opt-in.
- After successful `commit()` or `rollback()`, the next statement starts a new transaction lazily if `autoCommit=false`.
- The driver tracks active transaction state to avoid sending `COMMIT` or `ROLLBACK` when SQLite has no active transaction.
- Closing a connection with an active transaction must rollback where possible.
- `commit()` and `rollback()` must close/finalize active cursors first or reject with `SQLException` before changing transaction state. If cursor cleanup fails, transaction state is uncertain and pooled physical connections must be evicted.

`setAutoCommit(true)` while a transaction is active should commit first according to JDBC expectations. If that commit fails, `autoCommit` state must not be silently changed.

Commit/rollback acknowledgement rules:

- The driver must not mark a transaction committed until the backend confirms SQLite completed `COMMIT` successfully.
- The driver must not mark a transaction rolled back until the backend confirms SQLite completed rollback successfully.
- If `COMMIT` fails, the transaction remains active unless SQLite proves otherwise; if state cannot be proven, the connection is broken.
- If `ROLLBACK` fails, transaction state is unknown, the connection is high-risk, and pooled physical connections must be evicted.
- If SSH EOF, sqlite3/helper crash, protocol failure, or timeout happens after a mutating autocommit statement or `COMMIT` was sent but before success was received, the mutation outcome is unknown to JDBC and must not be retried automatically.

Failure edge cases:

| Failure | Required behavior |
| --- | --- |
| `commit()` fails due to busy/locked | Surface `SQLException`; connection may still have active transaction |
| `rollback()` fails | Surface `SQLException`; connection state should be treated cautiously |
| Backend dies mid-transaction | Connection broken; transaction outcome unknown unless SQLite rollback on close is certain |
| Close with active transaction | Attempt rollback, then close sqlite3/helper/channel |

Commit failure handling by class:

- `SQLITE_BUSY` and `SQLITE_LOCKED`: transaction may remain active and caller may decide whether to retry commit or rollback.
- `SQLITE_FULL`, `SQLITE_IOERR_*`, `SQLITE_CORRUPT`, `SQLITE_NOTADB`, timeout, sqlite3/helper crash, SSH EOF, or protocol failure: transaction outcome is unknown, connection is broken/tainted, and no automatic retry is allowed.
- `SQLITE_CORRUPT` and `SQLITE_NOTADB` require external integrity/restore workflow before further writes.

## SQLite Concurrency And WAL

This design can work with a live SQLite database in WAL mode because the backend opens SQLite locally on the server and participates in SQLite locking. That claim has conditions.

Requirements and caveats:

- The backend must be able to access the database file and WAL sidecar files, for example `app.db-wal` and `app.db-shm`.
- The database directory must permit the SQLite access pattern required by the selected read/write mode.
- Read-only WAL access may still need readable `-wal` and `-shm` files.
- `immutable=1` is only for snapshots, not live databases.
- Long-running reads can prevent checkpoints and cause WAL growth.
- Writes can block or fail if another client holds the writer lock.
- SQLite allows one writer at a time.
- Busy timeout expiry should surface as `SQLITE_BUSY` or an extended busy code.
- `SQLITE_LOCKED` is distinct from `SQLITE_BUSY` and should be reported distinctly.
- `SQLITE_BUSY_SNAPSHOT` can occur with stale read snapshots and should be mapped clearly.
- SSHSQLite does not upgrade SQLite durability beyond the database's configured journal and synchronous settings.
- For write sessions, `commit()` success means SQLite returned success for `COMMIT` under the active SQLite durability configuration.
- If power-loss durability is required, deployments must qualify the full storage stack: SQLite settings, filesystem, mount options, storage write cache, host power-loss behavior, and application transaction policy. WAL with `synchronous=FULL` is necessary for stronger SQLite durability but is not by itself a whole-system no-data-loss guarantee.
- The driver/backend must not change `journal_mode`, `synchronous`, `locking_mode`, or other durability-affecting PRAGMAs implicitly.
- For live WAL databases, backup/restore procedures require an atomic consistency boundary. Use SQLite online backup API, `VACUUM INTO` from an allowed backup workflow, application quiesce, or filesystem snapshot that atomically captures the database and WAL state. Sequentially copying `.db`, `-wal`, and `-shm` while writes continue is not a valid backup.
- If SSHSQLite ever provides a backup operation, it must use SQLite's online backup API or a documented atomic snapshot mechanism.

Possible pragmas for a read-mostly deployment, only if the operator accepts the durability tradeoff and the application supports them:

```sql
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA busy_timeout = 1000;
```

Backend connection setting:

```sql
PRAGMA busy_timeout = ?;
```

Admin workflow recommendation:

- Prefer `readonly=true` for inspection.
- Take a backup or snapshot before write-capable admin sessions.
- Keep transactions short.
- Avoid unbounded table scans against live servers.
- Avoid long-lived result sets while the game server is active.
- Remember that an open streaming `SELECT` can keep a read snapshot open until exhausted or closed.

## Schema Changes And Migrations

Canonical CLI mode does not disable schema changes when `readonly=false`; DDL and migration scripts are normal sqlite3 operations. Operators are responsible for backups and application quiescence before destructive or incompatible schema work.

Rules:

- Live application databases must not be migrated through SSHSQLite unless the operator has stopped or quiesced the application.
- `allowSchemaChanges` and `adminSql` are reserved for optional policy/helper backends. Canonical CLI mode allows normal sqlite3 scripting when `readonly=false`.
- Failed schema changes may leave application-level state incompatible even when SQLite remains structurally consistent.
- Schema changes are outside the no-data-loss guarantee unless a migration plan, rollback plan, and restore-tested backup exist.

## Busy Handling, Timeouts, And Cancellation

Busy timeout and query timeout must be handled separately.

| Control | Scope | Notes |
| --- | --- | --- |
| `busyTimeoutMs` | Waiting for SQLite locks | Does not stop CPU-heavy queries |
| `Statement.setQueryTimeout()` | Total statement runtime | CLI mode aborts the sqlite3 process if it cannot safely interrupt the work |
| `Statement.cancel()` | User-requested cancellation | Requires `sqlite3_interrupt()` or equivalent |
| SSH read/write timeout | Transport stalls | May break connection |

Rules:

- Set SQLite busy timeout per backend connection before user statements run.
- Map lock timeout to `SQLException` with SQLState `HYT00` and SQLite vendor code where applicable.
- Do not retry writes automatically. Any future retry policy must be limited to proven-idempotent operations and documented explicitly.
- Query timeout must not leave SQLite work running after JDBC reports timeout.
- `Statement.cancel()` must throw `SQLFeatureNotSupportedException` unless a real control path and `sqlite3_interrupt()` cleanup are implemented.
- Query timeout with verified `sqlite3_interrupt()` and successful statement finalization may leave the connection usable.
- If cancellation cannot be implemented safely, query timeout must terminate the backend and mark the connection broken.
- If backend termination happens while a transaction may be active, transaction outcome is unknown, the connection is broken, and pools must evict the physical connection without reuse.

## Metadata Queries

Use `sqlite_master` for table/view discovery. `sqlite_schema` is only an alias in newer SQLite releases and is not available on SQLite 3.27.2, which is a supported remote CLI version. SQLite treats `main` as the primary database/schema, so user SQL and metadata SQL must treat `main.table_name` as valid rather than stripping or rewriting it.

Tables and views:

```sql
SELECT name, type
FROM "main".sqlite_master
WHERE type IN ('table', 'view')
  AND name NOT LIKE 'sqlite_%'
ORDER BY name;
```

Databases/schemas:

```sql
PRAGMA database_list;
```

Columns use schema-qualified `PRAGMA table_xinfo` to include hidden/generated columns where available. Generated columns only appear on SQLite versions that support generated columns.

```sql
PRAGMA main.table_xinfo('table_name');
```

Indexes:

```sql
PRAGMA main.index_list('table_name');
PRAGMA main.index_xinfo('index_name');
```

Foreign keys:

```sql
PRAGMA main.foreign_key_list('table_name');
```

Identifier safety:

- Prefer schema-qualified PRAGMA statements for SQLite 3.27 compatibility.
- Quote schema identifiers with SQLite double-quote rules and SQL-literal quote table/index names.
- Never interpolate table or index names without identifier quoting.
- Include at least `main` and `temp` schemas.
- Attached databases are optional and must obey security policy.

## Generated Keys

Generated-key behavior is explicit:

- `DatabaseMetaData.supportsGetGeneratedKeys()` returns `true` for basic generated-key support.
- `Statement.execute(sql, RETURN_GENERATED_KEYS)`, `Statement.executeUpdate(sql, RETURN_GENERATED_KEYS)`, and `prepareStatement(sql, RETURN_GENERATED_KEYS)` return a one-column result set named `GENERATED_KEY` containing SQLite `last_insert_rowid()` from the executed statement.
- Column-name and column-index generated-key selection remains unsupported.
- `last_insert_rowid()` has SQLite's normal caveats for triggers, virtual tables, and `WITHOUT ROWID` tables.
