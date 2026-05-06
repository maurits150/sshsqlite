# Protocol

## Transport

The canonical protocol runs over the stdin/stdout streams of a remote `sqlite3` CLI process started through an SSH exec channel. Any length-prefixed JSON helper protocol described in older sections is legacy/optional/internal and is not required for normal use.

- stdout is query/result output only in the configured CLI mode.
- stderr is diagnostic-only and must be captured separately from stdout.
- stdin and stdout remain open for the full `sqlite3` process lifetime where practical.
- The driver sends controlled dot commands and SQL over the same stdin stream.
- The `sqlite3` CLI sends result output over stdout.
- EOF on either stream is a connection-level event, not a normal statement boundary.
- The data stream is sequential with at most one active statement per connection in MVP.
- Cancellation is SSH channel close or process destroy unless a true interrupt path exists.
- Current CLI mode buffers each statement response up to `cli.maxBufferedResultBytes`; future streaming-friendly CLI modes may consume rows incrementally.

Command shape:

```text
<sqlite3.path> [fixed safe options] [-readonly when readonly=true] <remote database path>
```

The JDBC URL path or `db.path` property is passed as the remote SQLite database path argument to `sqlite3` using safe command construction. SQL is written to stdin only and must not be interpolated into the SSH command string.

The implementation uses deterministic `sqlite3.path` default `/usr/bin/sqlite3` unless the connection explicitly supplies another safe path.

## CLI Modes

Use stable `sqlite3` CLI modes where practical:

- Use `.headers on`, `.mode csv`, and a fixed `.nullvalue` sentinel for portable SQLite CLI output. Do not require `.mode json`; it is unavailable on SQLite 3.27.2.
- Use a controlled separator/list mode only with delimiters that are configured by the driver and parsed unambiguously.
- Send only driver-controlled dot commands such as output mode, headers, null value, timeout, and optional safe diagnostics.
- Reject or never expose dangerous dot commands including `.shell`, `.system`, `.read`, `.open`, `.load`, and arbitrary user-supplied dot commands.
- Treat stderr as diagnostics, not as protocol data.
- If a CLI mode buffers all rows before exposing a cursor, the driver must enforce `cli.maxBufferedResultBytes` and apply `maxRows` before returning rows to JDBC. `fetchSize` must not be described as true network streaming for this path.

Parameter binding through the `sqlite3` CLI may be emulated or limited unless code implements a true binding path. Any emulation must quote values for SQLite input, never for shell syntax, and must be documented as less capable than direct SQLite prepared-statement binding. Acceptable CLI strategies include safe SQL literal rendering and controlled temporary parameter commands.

Read/write behavior is controlled by connection mode. With `readonly=true`, the command includes `-readonly` and the driver rejects mutations. With `readonly=false`, the command omits `-readonly` and write SQL is allowed through stdin.

## Stream Liveness

The SSH exec channel is the database connection. Keeping that channel and the `sqlite3` stdin/stdout streams alive is required for production behavior where the selected CLI workflow supports persistent use.

Rules:

- The driver must not start one SSH command per query.
- The `sqlite3` process should not exit after a successful statement.
- The driver must not close `sqlite3` stdin until `Connection.close()` or fatal failure.
- CLI output writes and reads must be deadline-bound or tied to the request context where the transport can enforce it.
- If stdout blocks past its deadline, the driver must close/destroy the process and mark the connection broken.
- Tests must prove no stuck `sqlite3` process remains after stdout stalls.
- The current driver runs one bounded stdout read task per active CLI statement. A future streaming CLI implementation may replace this with a continuous connection-lifetime reader.
- The driver must drain stderr independently so diagnostic output cannot block protocol progress or corrupt CSV stdout parsing.
- Idle validation uses a lightweight CLI validation query over the existing stream.
- SSH keepalives should be enabled separately from CLI validation.
- If validation fails or times out, the connection is broken and must be closed.

Suggested liveness defaults:

```text
protocol.pingIntervalMs=30000
protocol.pingTimeoutMs=5000
transport.writeTimeoutMs=10000
stderr.maxBufferedBytes=65536
```

The current implementation has no separate `transport.readTimeoutMs` property. Liveness comes from SSH EOF, SSH keepalive failure, CLI validation failure, query timeout, or cancellation when the selected backend supports it.

## Framing Rules

This section applies only to optional helper mode. CLI mode has no length-prefixed frame format; equivalent limits apply to CLI stdout parsing and buffered stderr.

Production implementations must enforce limits before allocation.

- `length` must be greater than zero.
- `length` must be less than or equal to negotiated `maxFrameBytes`.
- Negative lengths are impossible on the wire but must not appear after integer conversion.
- Oversized frames are fatal protocol errors.
- Truncated frames are fatal protocol errors.
- Malformed JSON is a fatal protocol error.
- JSON objects with duplicate keys are fatal protocol errors.
- Known fields must have exactly the expected JSON type before the helper acts on a request.
- Unknown critical fields in `hello`, `open`, transaction, or security-sensitive operations must be rejected.
- Unknown top-level fields should be ignored unless they conflict with a known field.
- Unknown operations must return a structured unsupported-operation error if framing remains synchronized.
- Duplicate request IDs are errors.
- After frame desynchronization, the connection must be closed rather than trying to recover.
- The protocol has no replay recovery. Truncated frames, dropped bytes, frame gaps, stale frames, or desynchronization are fatal.

Suggested initial limits:

```text
maxFrameBytes=1048576
maxSqlBytes=1048576
maxParamCount=min(configuredLimit, sqliteVariableLimit)
maxParamBytes=10485760
maxBatchRows=500
maxBatchBytes=1048576
maxBlobBytes=16777216
```

Limits must be configurable downward. Raising limits requires explicit configuration. `sqliteVariableLimit` comes from `sqlite3_limit(SQLITE_LIMIT_VARIABLE_NUMBER)` after the database connection is opened; `999` is an acceptable conservative configured default for early builds.

## Handshake

Legacy/internal helper mode starts with `hello`. Canonical CLI mode instead validates startup by running controlled `sqlite3` setup/diagnostic commands, such as version/output-mode checks, before exposing the JDBC connection.

Request:

```json
{
  "id": 1,
  "op": "hello",
  "protocolVersion": 1,
  "driverVersion": "0.1.0",
  "minProtocolVersion": 1
}
```

Response:

```json
{
  "id": 1,
  "ok": true,
  "op": "helloAck",
  "protocolVersion": 1,
  "helperVersion": "0.1.0",
  "sqliteVersion": "3.45.0",
  "os": "linux",
  "arch": "amd64",
  "sqliteBinding": "cgo dynamic sqlite via github.com/mattn/go-sqlite3 unless build tags/toolchain provide static linking",
  "sqliteCompileOptions": [
    "THREADSAFE=1"
  ],
  "compileTimeCapabilities": [
    "cgo",
    "sqliteVersion",
    "sqliteCompileOptions",
    "readonlyAuthorizer",
    "sqliteInterrupt",
    "extendedResultCodes",
    "prepareTailValidation",
    "sqliteLimits",
    "extensionLoadingDisabled"
  ],
  "capabilities": [
    "query",
    "open",
    "cursorFetch",
    "readonlyAuthorizer"
  ],
  "limits": {
    "maxFrameBytes": 1048576,
    "maxBatchRows": 500,
    "maxBatchBytes": 1048576
  }
}
```

The driver must refuse CLI mode when startup validation fails, the configured output mode is unavailable, or the `sqlite3` process exits unexpectedly. Helper version/capability checks apply only to optional helper mode.

`controlCancel` is an optional capability and must appear only when a separate control path or concurrent control reader is actually implemented.

## Open Database

Production startup passes the JDBC URL path or `db.path` as the remote SQLite database path argument to `sqlite3` using safe command construction. SQL, parameter values, and user-controlled SQLite options must not be put on the remote shell command line. `readonly=true` adds `-readonly`; `readonly=false` omits it. Busy timeout, output mode, and other connection settings are applied through fixed driver-controlled CLI options or stdin dot commands.

The framed `open` request below is retained only for optional helper mode.

Request:

```json
{
  "id": 2,
  "op": "open",
  "dbPath": "/srv/app/app.db",
  "readonly": true,
  "writeBackupAcknowledged": false,
  "adminSql": false,
  "allowSchemaChanges": false,
  "busyTimeoutMs": 1000,
  "queryOnly": true
}
```

Response:

```json
{
  "id": 2,
  "ok": true,
  "op": "openAck",
  "canonicalDbPath": "/srv/app/app.db",
  "readonly": true,
  "wal": true,
  "sqliteVariableLimit": 32766,
  "allowlistVersion": 1,
  "allowlistHash": "sha256:redacted-or-prefix"
}
```

CLI open rules:

- The database path from the URL or `db.path` is the SQLite database path argument.
- The path must be escaped as one shell word when constructing the SSH exec command string.
- SQL is written only after startup validation succeeds.
- If startup times out, exits, or produces unparsable validation output, the connection fails and the driver must start a fresh process for retry.
- CLI mode relies on SSH account permissions and server operator controls for filesystem authorization. It must not claim helper-style server-side allowlist enforcement unless a helper backend is selected.
- `readonly=false` is allowed for normal read/write use. A production safety property may require backup acknowledgement in hardened deployments, but it must not be required for basic DBeaver read/write setup.
- `adminSql` and `allowSchemaChanges` are reserved for optional policy/helper backends. Canonical CLI mode allows normal sqlite3 scripting when `readonly=false`.
- SQLite open errors are structured setup errors, not protocol corruption.

## Common Request Fields

This section applies only to optional helper mode. CLI mode uses driver-internal request tracking, not remote JSON request IDs.

All requests after `hello` include:

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `id` | integer | yes | Positive request ID unique for the connection |
| `op` | string | yes | Operation name |
| `timeoutMs` | integer | no | Total operation timeout |

IDs must be monotonically increasing for easier diagnostics. The helper should reject zero and negative IDs.

## Response Correlation And Retry Rules

This section applies only to optional helper mode. CLI mode must follow the same at-most-once retry principles for statements written to stdin.

The MVP data stream has exactly one pending data-stream request at a time.

Rules:

- Every response must match the pending request `id` and expected response operation family.
- Unsolicited responses are fatal protocol errors.
- Duplicate responses are fatal protocol errors.
- Stale response IDs are fatal protocol errors.
- Out-of-order data-stream responses are fatal protocol errors.
- Control-channel responses, if cancellation is implemented, are correlated independently from the data stream.
- Once the driver has successfully written a request frame, it must not retry that request on the same helper after timeout, EOF, protocol error, or unknown response state unless the operation is explicitly documented idempotent.
- `query`, `fetch`, `exec`, `begin`, `commit`, `rollback`, `open`, and `closeCursor` are not retried on the same helper after request write completion.
- SSH is an ordered byte stream. Missing responses are treated as timeout, EOF, or protocol failure, not packet loss to recover from.

Mutation outcome rules:

- Mutating operations are at-most-once from the driver's send path, but not exactly-once observable to JDBC if the connection fails after the request is written.
- The driver must never automatically retry `exec`, `commit`, or non-idempotent SQL after a timeout, EOF, helper crash, or response-correlation failure.
- If a mutating request was written and no definitive response was received, the outcome is unknown, the connection is broken, and any pooled physical connection must be evicted.

## Positional And Named Parameters

Prepared-statement parameter support depends on backend capability. Direct helper mode can support standard JDBC positional binding and optional SQLite named binding. CLI mode may implement binding by safe SQL literal rendering or controlled temporary parameter commands, and must document any unsupported setter/value types or placeholder forms.

JDBC `PreparedStatement` setters bind by SQLite parameter index, regardless of placeholder spelling. SQLite assigns indexes to `?`, `?NNN`, `:name`, `@name`, and `$name` placeholders. Repeated named placeholders share the same SQLite parameter index.

Indexed parameters use `params` as an array and bind by SQLite parameter index:

```json
{
  "sql": "SELECT * FROM Track LIMIT ? OFFSET ?",
  "params": [
    {"type":"integer","value":50},
    {"type":"integer","value":0}
  ]
}
```

The same indexed form is valid for named SQL placeholders when called through standard JDBC setters:

```json
{
  "sql": "SELECT _rowid_ AS rowid, * FROM Track LIMIT :limit OFFSET :offset",
  "params": [
    {"type":"integer","value":50},
    {"type":"integer","value":0}
  ]
}
```

Named parameters use `namedParams` as a non-standard extension and bind by exact SQLite parameter name, including the leading prefix:

```json
{
  "sql": "SELECT _rowid_ AS rowid, * FROM Track LIMIT :limit OFFSET :offset",
  "namedParams": {
    ":limit": {"type":"integer","value":50},
    ":offset": {"type":"integer","value":0}
  }
}
```

Rules when true SQLite binding is implemented:

- The helper must prepare the statement first, then validate supplied parameters against SQLite-reported parameter metadata such as `sqlite3_bind_parameter_count()` and `sqlite3_bind_parameter_name()`.
- Missing parameters must fail before stepping.
- Extra parameters must fail fast to catch spelling mistakes.
- Mixing `params` and `namedParams` in one request must be rejected for MVP.
- `?NNN` placeholders bind according to SQLite's parameter index rules, not array position in the SQL text.
- Sparse `?NNN` placeholder indexes are rejected for MVP unless the support matrix explicitly documents and tests them. The driver/helper must never rewrite SQL to normalize parameter indexes.
- Repeated named placeholders receive the same bound value because SQLite exposes one index for that name.
- `$` parameter names may use SQLite's accepted grammar; if the helper restricts names, the restriction must be documented and tested.
- Parameter values are bound through SQLite APIs, never interpolated into SQL.
- Named parameter names are not identifiers and must not be quoted as SQL identifiers.

Rules for CLI emulation, if used before true binding exists:

- Supported placeholder forms are `?`, `?NNN`, `:name`, `@name`, and `$name` with JDBC index-based setters. Sparse `?NNN` indexes are rejected, and repeated named placeholders reuse one value.
- Supported values are null, signed integers, finite reals, UTF-8 text, booleans rendered as integer `1`/`0`, dates/times/timestamps rendered as text, `BigDecimal` rendered as text, and byte arrays rendered as hex BLOB literals.
- Escape values for SQLite SQL text only; never pass values through shell syntax and never include values in the SSH exec command.
- If temporary `.parameter` commands are used, generate only driver-controlled commands and clear parameter state between executions.
- Treat unsupported placeholders or value types as `SQLFeatureNotSupportedException` or a clear `SQLException`.
- Do not claim full JDBC `PreparedStatement` semantics until tests prove them.

## Value Encoding

SQLite values are encoded by storage class.

```json
{"type":"null"}
{"type":"integer","value":123}
{"type":"real","value":12.5}
{"type":"text","value":"hello"}
{"type":"blob","base64":"AAEC"}
```

Rules:

- `null` has no `value`.
- Integers must fit signed 64-bit range.
- Reals are JSON numbers and must reject NaN and infinity.
- Text is UTF-8 JSON string data.
- Helper/internal JSON value objects encode BLOBs as base64. CLI CSV mode cannot reliably preserve arbitrary BLOB bytes without an explicit SQL projection such as `hex(blob_column)`; generic BLOB streaming is not part of the SQLite 3.27-compatible CLI MVP.
- Parameters may use the explicit object form above.
- A shorthand JSON primitive form may be accepted only if documented as compatibility sugar.

Explicit typed values avoid ambiguity between SQL `NULL`, JSON `null`, text, bytes, and integer/real conversions.

## Query

Production query flow must avoid unbounded client memory. Helper mode can use cursor/fetch. Current CLI mode reads one CSV statement response into a bounded buffer, parses it, then exposes an in-memory cursor to JDBC. Future CLI implementations may stream CSV parser output, but must still enforce limits for any buffered path.

Current CLI mode enforces `cli.maxBufferedResultBytes` for each sqlite3 statement response. Exceeding the limit aborts sqlite3, marks the JDBC connection broken, and prevents an unbounded read lock or heap growth. `Statement.setMaxRows()` is applied after parsing the bounded response; it limits rows returned to JDBC but does not reduce bytes read from sqlite3 in the current buffered implementation.

Start request:

```json
{
  "id": 10,
  "op": "query",
  "sql": "SELECT id, name FROM grid_objects WHERE id = ?",
  "params": [
    {"type":"integer","value":123}
  ],
  "maxRows": 1000,
  "fetchSize": 200,
  "timeoutMs": 30000
}
```

Start response:

```json
{
  "id": 10,
  "ok": true,
  "op": "queryStarted",
  "cursorId": "c10",
  "columns": [
    {"name":"id","declaredType":"INTEGER","sqliteType":"integer","nullable":true},
    {"name":"name","declaredType":"TEXT","sqliteType":"text","nullable":true}
  ]
}
```

Fetch request:

```json
{
  "id": 11,
  "op": "fetch",
  "cursorId": "c10",
  "maxRows": 200,
  "timeoutMs": 30000
}
```

Fetch response:

```json
{
  "id": 11,
  "ok": true,
  "op": "rowBatch",
  "cursorId": "c10",
  "rows": [
    [
      {"type":"integer","value":123},
      {"type":"text","value":"terrain_0_0"}
    ]
  ],
  "done": false
}
```

Later fetch request:

```json
{
  "id": 12,
  "op": "fetch",
  "cursorId": "c10",
  "maxRows": 200,
  "timeoutMs": 30000
}
```

Final fetch response:

```json
{
  "id": 12,
  "ok": true,
  "op": "rowBatch",
  "cursorId": "c10",
  "rows": [],
  "done": true,
  "rowCount": 1,
  "truncated": false
}
```

Close request:

```json
{
  "id": 13,
  "op": "closeCursor",
  "cursorId": "c10"
}
```

Close response:

```json
{
  "id": 13,
  "ok": true,
  "op": "closeCursorAck",
  "cursorId": "c10",
  "finalized": true
}
```

Query rules:

- CLI mode sends SQL on stdin after configuring output mode. SQL must not be sent on the SSH command line.
- True `params`/`namedParams` binding is available only when implemented by the selected backend; otherwise parameter support is emulated/limited as documented.
- In helper/streaming backends, `queryStarted` means the statement is prepared and columns are known, not that all rows are buffered. In current CLI mode, the bounded CSV response has already been read and parsed before `queryStarted` is returned.
- The backend must finalize or complete the active statement when the result is exhausted, the result set is closed, or the connection closes where the CLI/backend makes that observable.
- Multi-statement handling must be explicit. Current CLI mode allows normal sqlite3 scripts for write/update execution when they produce no result rows; JDBC update count is the final SQLite `changes()` value after the script. Multi-result-set behavior is not supported.
- Statements that do not return rows must fail when invoked through `executeQuery()`.
- `maxRows` limits total returned rows.
- `fetchSize` is advisory for helper/streaming backends. In current CLI mode it controls only how many already-buffered rows are returned per JDBC fetch.
- Helper/streaming backends must stop stepping once `maxRows` is reached and mark `truncated=true` if appropriate. Current CLI mode truncates the in-memory cursor to `maxRows` after parsing and does not currently report a protocol-level `truncated` flag.
- Closing a JDBC `ResultSet`, closing its `Statement`, or executing a new statement on the same connection must send `closeCursor` when possible.
- `closeCursor` is idempotent for the currently owned cursor and for an already-finalized cursor on the same connection.
- Unknown cursor IDs from another generation or request must return a structured error.
- If an active CLI query cannot be cleanly abandoned, the driver must close the SSH channel/process and mark the connection broken.
- A timed-out query may have advanced SQLite even if its output was not delivered. The driver must not retry blindly; it must either prove cleanup or close the process and mark the connection broken.
- In helper/streaming backends with an active remote cursor, an `autoCommit=true` `SELECT` may keep a read transaction open until the cursor is exhausted or closed. Current CLI mode buffers the statement response before returning the JDBC result set, so it does not leave a remote SQLite cursor open after `queryStarted`.

## Exec

`exec` is required for read/write CLI connections and is disabled only when `readonly=true` or when the driver has not implemented update execution.

Request:

```json
{
  "id": 11,
  "op": "exec",
  "sql": "UPDATE grid_objects SET version = version + 1 WHERE id = ?",
  "params": [
    {"type":"integer","value":123}
  ],
  "timeoutMs": 30000
}
```

Response:

```json
{
  "id": 11,
  "ok": true,
  "op": "execResult",
  "changes": 1,
  "lastInsertRowid": 123
}
```

Exec rules:

- Multi-statement handling must be explicit. Current CLI mode allows normal sqlite3 scripts for `executeUpdate()` when they produce no result rows; statements that produce CSV rows are not valid update execution.
- The driver must bind or safely render either indexed `params` or `namedParams` before execution according to the selected CLI parameter strategy.
- Statements that return rows must fail when invoked through `executeUpdate()`.
- `changes` maps to JDBC update count where applicable and comes from SQLite `changes()` after successful execution. Statements that do not affect rows, including most DDL, report SQLite's `changes()` value, typically `0`.
- `lastInsertRowid` is informational unless generated-keys support is explicitly requested and implemented.

## Transactions

Transaction support is part of read/write CLI behavior when manual JDBC transactions are implemented. In CLI mode, transactions are sent as SQL (`BEGIN`, `COMMIT`, `ROLLBACK`) over stdin and must follow the same timeout and unknown-outcome rules as other mutating statements.

Requests:

```json
{"id":12,"op":"begin","mode":"deferred"}
{"id":13,"op":"commit"}
{"id":14,"op":"rollback"}
```

Allowed `mode` values:

| Mode | SQL | Use |
| --- | --- | --- |
| `deferred` | `BEGIN DEFERRED` | Default; avoids taking write lock until needed |
| `immediate` | `BEGIN IMMEDIATE` | Optional explicit write intent |
| `exclusive` | `BEGIN EXCLUSIVE` | Rare; requires explicit opt-in |

The helper must report transaction-state errors cleanly. The driver should track transaction state to avoid sending invalid `COMMIT` or `ROLLBACK` when no transaction is active.

Commit and rollback acknowledgement rules:

- The helper may send `commit` success only after SQLite has completed `COMMIT` successfully and all related SQLite calls have returned success.
- The helper may send `rollback` success only after SQLite confirms rollback completed.
- The driver must not mark a transaction committed or rolled back until the matching success response is received.
- If `COMMIT` fails, the transaction state remains active unless SQLite reports otherwise; if state cannot be proven, the connection is broken.
- If `ROLLBACK` fails, transaction state is unknown, the connection is high-risk, and any pooled physical connection must be evicted.
- If SSH EOF, helper crash, protocol failure, or timeout happens after `commit`, `rollback`, or a mutating autocommit `exec` was written but before a definitive response, the outcome is unknown to JDBC.

## Metadata Operations

The protocol may expose metadata operations for common tool workflows, but metadata can also be implemented by issuing normal SQLite introspection SQL.

Suggested MVP metadata request:

```json
{"id":15,"op":"metadata","kind":"tables","schema":"main"}
```

The backend must safely quote identifiers. CLI mode uses SQLite 3.27-compatible schema-qualified PRAGMA statements for metadata. Metadata operations must never concatenate untrusted identifiers without SQLite identifier quoting.

## Ping

Request:

```json
{"id":16,"op":"ping"}
```

Response:

```json
{"id":16,"ok":true,"op":"pong"}
```

`Connection.isValid(timeout)` uses `ping` only when no data request, cursor fetch, exec, or transaction operation is pending. During active work, liveness comes from operation deadline, SSH EOF/keepalive, or an implemented control channel.

## Cancellation

Cancellation in canonical CLI mode is SSH channel close or process destroy. A true interrupt path, such as `sqlite3_interrupt()`, exists only if a helper/direct SQLite backend implements it.

Production cancellation requires one of these explicit capabilities:

- A second SSH exec channel connected to the same helper as a control stream.
- A helper architecture where one goroutine continuously reads control frames while another executes SQLite work.

Required behavior when `cancel` capability is advertised:

- The helper executes SQLite work in a cancellable context.
- `Statement.setQueryTimeout()` installs a helper-side deadline.
- `Statement.cancel()` sends a `cancel` request over the defined control path, not the blocked data request loop.
- The helper calls `sqlite3_interrupt()` or equivalent for the active SQLite connection.
- The helper finalizes the interrupted statement before reporting the connection reusable.
- `cancelAck` means the interrupt request was accepted; it does not by itself mean the target operation is complete or the connection is reusable.
- The target operation must still produce a definitive interrupted/error terminal response, and the active statement/cursor must be finalized before reuse.

Cancel request:

```json
{"id":17,"op":"cancel","targetId":10}
```

Cancel response:

```json
{"id":17,"ok":true,"op":"cancelAck","targetId":10}
```

If safe cancellation is not implemented, `Statement.cancel()` may close/destroy the active SSH channel/process or throw `SQLFeatureNotSupportedException`, as documented by the driver. Query timeout must terminate the `sqlite3` process and mark the connection broken rather than pretending the statement was cleanly canceled.

If a query timeout kills the CLI process while a transaction may be active, the transaction outcome is unknown from JDBC's perspective. The connection is broken, any pooled physical connection must be evicted without reuse, and callers must reconnect before issuing more SQL.

Busy timeout and query timeout are different:

- Busy timeout controls how long SQLite waits for locks.
- Query timeout controls total statement runtime.
- Busy timeout does not interrupt CPU-heavy queries.
- Query timeout must not silently leave a statement running after JDBC reports cancellation.
- Query timeout with verified `sqlite3_interrupt()` and successful cleanup may leave the connection usable.
- Query timeout without safe interrupt is connection-fatal.

Timeout teardown state machine:

1. Pending data request exceeds its deadline.
2. Driver marks the physical connection broken and tainted.
3. Driver closes sqlite3/helper stdin/stdout and the SSH channel/session with bounded waits.
4. Driver fails all waiters and rejects concurrent JDBC calls with connection-broken `SQLException`.
5. Pool, if present in a future release, evicts the physical connection without ping-based reuse.

## Error Responses

Error response:

```json
{
  "id": 11,
  "ok": false,
  "op": "error",
  "message": "database is locked",
  "code": "SQLITE_BUSY",
  "extendedCode": "SQLITE_BUSY_TIMEOUT",
  "sqlState": "HYT00",
  "vendorCode": 5,
  "connectionBroken": false,
  "retryable": true
}
```

Required fields:

| Field | Meaning |
| --- | --- |
| `message` | Sanitized human-readable error |
| `code` | SQLite primary code or driver/protocol code |
| `extendedCode` | SQLite extended code when available |
| `sqlState` | JDBC SQLState chosen by driver/helper contract |
| `vendorCode` | SQLite numeric result code when available |
| `connectionBroken` | Whether the JDBC connection must be discarded |
| `retryable` | Whether retry might succeed without changing SQL/config |

The protocol must not include secrets, private key paths with sensitive usernames, password material, or SQL parameter values in errors by default.

## Error Mapping

Suggested JDBC mapping:

| Condition | SQLState | JDBC exception style | Connection broken |
| --- | --- | --- | --- |
| `SQLITE_BUSY` timeout | `HYT00` | transient timeout | Usually no |
| `SQLITE_LOCKED` | `HY000` | transient/resource conflict | Usually no |
| `SQLITE_READONLY` | `25006` | read-only violation | No |
| `SQLITE_CONSTRAINT_*` | `23000` | integrity constraint | No |
| `SQLITE_CORRUPT` | `XX001` | database corrupted | Maybe yes |
| `SQLITE_NOTADB` | `XX001` | not a database/corruption | Yes |
| `SQLITE_IOERR_*` | `58030` | I/O error | Maybe yes |
| `SQLITE_FULL` | `53100` | disk full | Maybe yes; transaction state cautious |
| `SQLITE_NOMEM` | `53200` | resource exhaustion | Maybe yes |
| `SQLITE_CANTOPEN` | `08001` or `58030` | cannot open database/file | No connection or maybe broken |
| SQLite syntax error | `42000` | bad SQL grammar | No |
| Query timeout with safe interrupt and cleanup | `HYT00` | timeout | No |
| Query timeout without safe interrupt | `HYT00` | timeout | Yes |
| Helper crash | `08006` | connection failure | Yes |
| SSH EOF | `08006` | connection failure | Yes |
| Protocol violation | `08P01` | protocol violation | Yes |
| Host key failure | `08001` | connection failure | No connection |
| Path not allowed | `28000` or `42501` | authorization failure | No connection or no statement |

## Resource Limit Errors

Limit failures must happen before expensive allocation or execution where possible.

Examples:

- SQL text too large.
- Too many parameters.
- Parameter value too large.
- BLOB too large for the selected backend encoding.
- Row batch exceeds negotiated byte limit.
- Result exceeds `maxRows` when truncation is disabled.
- Frame exceeds `maxFrameBytes`.

Limit errors should not break the connection unless framing has already become unsafe.
