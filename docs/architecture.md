# Architecture

## Overview

SSHSQLite is a JDBC driver that runs the remote `sqlite3` CLI over SSH:

Phase 0 implementation choices are recorded in `docs/design-decisions.md`; this architecture document describes the resulting component boundaries and lifecycle.

```text
DBeaver/custom Java app
  -> SSHSQLite JDBC driver
  -> SSH session
  -> SSH exec channel
  -> remote sqlite3 CLI opens /path/to/database.db locally
```

The `sqlite3` process is owned by one JDBC connection where practical. The driver sends controlled SQLite CLI input on stdin and reads stable CLI output from stdout. stderr is reserved for diagnostics and must never be parsed as query results.

## Components

### Java JDBC Driver

Current read/write CLI responsibilities:

- Register JDBC URL prefix `jdbc:sshsqlite:`.
- Parse JDBC URLs and connection properties with deterministic precedence rules.
- Verify the SSH host key before authentication.
- Authenticate through SSH using a configured private key, a discovered standard private key from `~/.ssh`, or password authentication. SSH agent authentication is not supported by the self-contained driver jar today.
- Start the remote `sqlite3` CLI safely with a deterministic configured path.
- Pass the JDBC URL database path as the remote SQLite database path to `sqlite3`, using safe command construction.
- Maintain the `sqlite3` stdin/stdout streams with dedicated read/write handling so idle connections remain usable where the CLI mode supports it.
- Translate JDBC calls into controlled SQLite CLI input and dot commands.
- Translate SQLite CLI output into `ResultSet`, update counts, metadata, and `SQLException` instances.
- Support read and write SQL through the `sqlite3` CLI. `readonly` is a connection mode, not a product-wide limitation.
- When `readonly=true`, pass `-readonly`, set `PRAGMA query_only=ON`, and reject JDBC write-method execution. SQLite still enforces statement-level read-only behavior for SQL sent through read paths. When `readonly=false`, run `sqlite3` without `-readonly` and allow write SQL subject to SQLite permissions and locking.
- Enforce JDBC method state transitions, close behavior, and timeouts. `Statement.cancel()` is currently unsupported in CLI mode; query timeout aborts the underlying backend and marks the connection broken.
- Close the `sqlite3` process, SSH channel, and SSH session deterministically.
- Provide optional `SshSqliteDataSource` pooling, capped at 5 physical connections when `pool.enabled=true`.

Minimal class set should include base adapters before concrete transport-aware classes:

```text
BaseDriver implements java.sql.Driver
BaseConnection implements java.sql.Connection
BaseStatement implements java.sql.Statement
BasePreparedStatement extends BaseStatement implements java.sql.PreparedStatement
BaseResultSet implements java.sql.ResultSet
BaseResultSetMetaData implements java.sql.ResultSetMetaData
BaseDatabaseMetaData implements java.sql.DatabaseMetaData

SshSqliteDriver implements java.sql.Driver
SshSqliteConnection implements java.sql.Connection
SshSqliteStatement implements java.sql.Statement
SshSqlitePreparedStatement implements java.sql.PreparedStatement
SshSqliteResultSet implements java.sql.ResultSet
SshSqliteResultSetMetaData implements java.sql.ResultSetMetaData
SshSqliteDatabaseMetaData implements java.sql.DatabaseMetaData
Transport
SshTransport
LocalProcessTransport
CliProtocolClient
ProtocolClient
InMemoryResultSet
```

The base adapters implement every method in the targeted Java `java.sql` interfaces. Concrete classes override supported behavior only. This prevents accidental `AbstractMethodError` when desktop tools call rarely used JDBC methods.

### Remote SQLite CLI

Canonical implementation: SSH exec of the remote `sqlite3` CLI. `sqlite3.path` is an explicit connection property with deterministic default `/usr/bin/sqlite3`. The implementation must construct the command safely.

Responsibilities:

- Open the requested remote SQLite database path from the JDBC URL or `db.path` property.
- Open SQLite with correct read-only/read-write flags.
- Use stable CLI output modes: `.headers on`, `.mode csv`, and a fixed `.nullvalue` sentinel. This avoids relying on `.mode json`, which older supported SQLite CLI versions lack.
- Execute only SQL supplied as CLI stdin, never interpolated into a shell command.
- Apply connection-local settings such as `.timeout`/busy timeout and safe output configuration.
- Serve query results through JDBC cursor/fetch APIs backed by a bounded in-memory CLI result buffer. This is not true network streaming; large results are limited by `cli.maxBufferedResultBytes`.
- Handle query timeout by closing the SSH channel or destroying the process and marking the connection broken unless a true interrupt path is implemented later. `Statement.cancel()` is unsupported in CLI mode.

`sshsqlite-helper` may still exist as a legacy, optional, or internal backend for experiments that need direct SQLite APIs such as authorizers or `sqlite3_interrupt`, but it is not required for normal use and must not be the default backend.

Write-capable CLI responsibilities:

- Execute updates and return changed row count and optional last insert rowid.
- Handle manual transactions and rollback on connection teardown where transaction state is known.
- Bind values for `PreparedStatement` execution through documented CLI-safe parameter handling.

## Connection Lifecycle

Connection startup must be bounded and deterministic:

1. Parse URL and properties.
2. Verify SSH host key against configured known hosts.
3. Authenticate to SSH.
4. Resolve `sqlite3.path` and the remote SQLite database path.
5. Open an SSH exec channel.
6. Start `sqlite3` with safe fixed command construction and the database path as the SQLite database argument.
7. Configure stable CLI mode and connection options through controlled stdin dot commands.
8. Return the JDBC `Connection` only after startup validation succeeds.

If any step fails, the driver must close all partially opened resources and throw a clear `SQLException` or authentication-specific wrapper according to JDBC conventions.

## SQLite CLI Process Lifecycle

The `sqlite3` process is owned by one JDBC connection.

- One JDBC connection starts one `sqlite3` process.
- The CLI stdin/stdout streams remain open across statements on that connection where practical.
- SQL and controlled dot commands use the same long-lived SSH exec channel, not per-query SSH commands.
- The driver must detect row batches, errors, EOF, and process death promptly during each active request. Current CLI mode uses a request-scoped reader plus bounded stderr capture rather than a continuously running stdout reader.
- Idle connections should be validated with a lightweight CLI query and SSH keepalive rather than by restarting `sqlite3`.
- Closing the JDBC connection closes CLI stdin, waits for process exit, then closes the SSH channel and session.
- Close must use a bounded timeout. If `sqlite3` does not exit, the SSH channel must be forcibly closed.
- CLI EOF, unparsable output, SSH channel close, non-zero process exit, or stderr-only fatal startup output marks the JDBC connection broken.
- A broken connection must reject further SQL with `SQLException`.
- The driver should send rollback before exit when an active transaction is known and the CLI is still usable.
- The driver must drain or capture CLI stderr for diagnostics without blocking the process.
- Startup must include a bounded handshake timeout and a `ping` or `hello` validation before exposing the connection.

Fatal connection conditions:

| Condition | Driver behavior |
| --- | --- |
| SSH authentication failure | Throw connection failure, no `sqlite3` process started |
| Host key unknown or changed | Fail closed before authentication |
| `sqlite3` executable missing | Throw clear startup `SQLException` |
| `sqlite3` exits during startup | Throw startup `SQLException` with sanitized stderr |
| Unparsable CLI output | Mark connection broken and close channel |
| SQLite busy timeout | Throw statement `SQLException`; connection usually remains usable |
| Query timeout in current CLI mode | Terminate `sqlite3` or close the SSH channel and mark connection broken |
| Future query timeout with safe interrupt and cleanup | Throw timeout `SQLException`; connection remains usable |
| Remote EOF during active request | Mark connection broken; active statement fails |

## Deployment And Compatibility

The primary compatibility contract is between the Java driver and the remote `sqlite3` CLI behavior it requires.

Required compatibility contract:

- The driver detects or documents the required `sqlite3` CLI features, including supported output modes.
- Release artifacts list supported client Java versions and tested server `sqlite3` versions/platforms.
- SQLite compile options that affect behavior must be visible through diagnostics.
- Operators are responsible for the trustworthiness of the remote `sqlite3` binary and server package management. Normal CLI mode does not require a helper hash or helper allowlist.

Initial supported target:

```text
client: Java 11+
server: any SSH server with a compatible sqlite3 CLI
sqlite: remote sqlite3 CLI with stable CSV output mode support; exact tested versions documented per release
```

## Concurrency Model

MVP concurrency is intentionally narrow.

- One JDBC connection maps to one SSH channel, one `sqlite3` process, and one SQLite connection.
- Only one data-stream work request is in flight per connection unless the protocol explicitly negotiates concurrent requests later.
- Only one active `ResultSet` is supported per connection in MVP.
- Executing a new statement closes any previous active result set on that connection.
- Concurrent calls on the same JDBC `Connection` must be synchronized or rejected with `SQLException`.
- Separate JDBC connections may run concurrently and start separate `sqlite3` processes.
- SQLite still permits only one writer at a time across all processes using the database.

## Optional Connection Pool

Plain `Driver.connect()` returns a physical JDBC connection backed by one SSH session, one `sqlite3` process, and one SQLite connection. Built-in pooling is available only through the explicit `SshSqliteDataSource` entry point when `pool.enabled=true`; it is not used by plain `Driver.connect()`.

Pooling contract:

- Pooling is optional and must be explicit.
- The built-in pool defaults to `pool.maxSize=5` when `pool.enabled=true`; configured values above 5 are capped at 5.
- Each pooled physical connection owns its own SSH session, `sqlite3` process, and SQLite connection.
- Pools are keyed by the configured security context: SSH host, port, username, authentication identity, known-host policy, `sqlite3.path`, database path, readonly mode, output mode, and helper/backend selection. Current CLI capabilities are fixed by that context; optional helper or future negotiated capability backends must include detected capabilities in the pool key.
- `db.path`, `readonly`, `sqlite3.path`, SSH identity, helper/backend selection, and any negotiated capabilities are immutable for a physical pooled connection.
- A read-write physical connection must never be reused for a readonly borrower.
- Borrowed connections must be validated with `ping` before reuse if idle beyond the validation threshold.
- Broken connections must be evicted immediately and never returned to the pool.
- Returning a connection to the pool must close open statements/result sets, rollback any active transaction, verify no active transaction remains, clear warnings/client info, and reset mutable connection state such as autocommit, schema/catalog, busy timeout, query timeout defaults, network timeout, holdability, effective logical readonly state, and parameter maps.
- Pool return must never call `setAutoCommit(true)` before rolling back active borrower work, because JDBC `setAutoCommit(true)` can commit.
- If rollback, reset, close, or validation fails, the physical connection must be evicted and closed rather than returned to idle.
- Any unknown-outcome mutation, commit-unknown, rollback-unknown, fetch-timeout-without-cleanup, output desynchronization, CLI EOF, abort, or cancel without confirmed finalization marks the physical connection tainted and non-poolable even if a later validation appears to work.
- Pool shutdown must close every `sqlite3` process and SSH session with bounded timeouts.
- Pooling must not hide SQLite write contention. Five pooled connections can improve responsiveness for independent reads and metadata, but SQLite still allows only one writer at a time.

Recommended initial pool settings:

```text
pool.enabled=false
pool.maxSize=5
pool.minIdle=0
pool.idleTimeoutMs=300000
pool.validationTimeoutMs=5000
pool.maxLifetimeMs=1800000
```

## Result Buffering And Fetching

Current CLI mode must not claim true network streaming. It reads one complete sqlite3 CSV response into a bounded buffer, parses it, and exposes rows through JDBC fetch calls from an in-memory cursor.

Current behavior:

- Query results use cursor/fetch flow control at the JDBC protocol layer: `queryStarted`, bounded `fetch`/`rowBatch` responses, and `closeCursor` or final `done` cleanup.
- The CLI client bounds total buffered result bytes with `cli.maxBufferedResultBytes` and aborts the backend if the bound is exceeded.
- The driver must not buffer an unbounded result set in memory.
- Closing a result set releases the in-memory cursor. The remote sqlite3 statement has already completed before rows are exposed to JDBC.
- `Statement.setMaxRows()` must be honored.
- `Statement.setFetchSize()` should influence batch size but must not override hard safety limits.
- If the desktop tool issues an unbounded query, the driver still applies configured byte limits.

Future true streaming would require row batches to be read incrementally from sqlite3 output without first materializing the full result. Until then, CLI mode is bounded-buffered and unsafe for very large unbounded result sets.

## Failure Model

Every failure must fall into one of these categories:

| Category | Examples | Connection usable afterward |
| --- | --- | --- |
| Authentication/configuration | bad key, bad password, bad URL, missing `sqlite3` | No connection returned |
| Authorization | readonly write rejected, optional helper allowlist rejection | Usually yes |
| SQLite statement error | syntax error, constraint failure, no such table | Yes unless SQLite reports corruption/I/O fatality |
| Locking | `SQLITE_BUSY`, `SQLITE_LOCKED`, busy timeout | Usually yes after statement cleanup |
| Query timeout with safe interrupt | deadline exceeded and cleanup succeeded | Usually yes |
| Query timeout without safe interrupt | deadline exceeded but SQLite cannot be interrupted safely | No |
| Protocol/process | unparsable output, `sqlite3` crash, SSH EOF | No |
| Integrity/security | changed host key, unsafe executable path | No connection returned |

The driver must distinguish statement-level failures from broken-connection failures so tools can decide whether to retry, reconnect, or show a query error.

On `sqlite3` crash, SSH EOF, client crash, abort, or timeout while a transaction or mutating request is active, SQLite is expected to roll back uncommitted transactions when the connection/process closes. The JDBC client still cannot prove whether a `COMMIT` or autocommit write completed unless it received the matching success response. The connection must be marked broken, pooled physical connections must be evicted, and callers must treat the last transaction or mutating statement outcome as unknown.
