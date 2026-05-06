# Design Decisions

These decisions describe the current implementation direction and constraints. They refine the architecture, protocol, security, and operations docs without replacing `AGENTS.md` or the README.

## Java Transport

- SSH/SFTP library: Apache MINA SSHD client, using `sshd-core` for normal SSH exec channels and `sshd-sftp` only for retained optional helper-integrity paths.
- Rationale: actively maintained Java SSH stack, supports SSH exec channels, is testable with embedded/containerized SSH fixtures, and avoids shelling out to `ssh`.
- Host-key verification: production uses OpenSSH-compatible `known_hosts` verification before authentication. Unknown, changed, revoked, unsupported, or mismatched host keys fail closed.
- Required known-host behavior: hashed entries, bracketed `[host]:port`, IPv6 bracket form, `@revoked`, and modern key algorithms must be supported by the selected MINA verifier or explicitly fail closed in tests. No trust-all verifier is exposed in production configuration.
- Remote command construction: use SSH exec with one validated command string for the configured `sqlite3.path` and the remote database path as the SQLite database argument. SQL is sent later on stdin and must never be interpolated into the command string. Because SSH exec transmits a command string to the server, the implementation must use one documented POSIX single-quote escaping function for the executable path, fixed arguments, and database path.
- `sqlite3.path`: the deterministic default is `/usr/bin/sqlite3`. No `helper.path` is required for normal CLI mode.
- Database path handling: the JDBC URL path or `db.path` property is passed as the remote SQLite database path to the `sqlite3` command using safe command construction. It is not concatenated into shell syntax and SQL is not passed on the command line.

## SQLite CLI And Database Authorization

- Production `sqlite3` path: use the configured `sqlite3.path` or the documented deterministic default `/usr/bin/sqlite3`. Operators are responsible for trusting the remote `sqlite3` binary and server package management.
- Normal CLI mode has no `helper.expectedSha256` or helper allowlist requirement. Optional/internal `sshsqlite-helper` mode may keep stricter helper integrity rules if it is retained.
- Production database authorization in CLI mode is primarily OS/account based: the SSH account should have least-privilege access only to intended database files. Driver-side path validation is safety hygiene, not a server-side security boundary.
- The CLI backend must not accept arbitrary SQLite shell dot commands from user SQL. Normal SQLite SQL, including DDL, PRAGMAs, transactions, `ATTACH`, and `VACUUM`, is allowed in read/write mode and relies on the remote OS account and SQLite for file permissions and locking.

## Optional Helper SQLite Binding

These decisions apply only if the legacy/internal `sshsqlite-helper` backend is retained. They are not required for the canonical CLI backend.

- Binding choice: `github.com/mattn/go-sqlite3` with cgo, built as a helper-owned binary for the supported Linux targets.
- Rationale: mature production SQLite binding, widely deployed, supports custom SQLite builds/build tags, exposes `database/sql` plus driver-level connection access, and can be extended with a small internal cgo shim if a required SQLite C API is not surfaced directly.
- Required helper spike: before broad helper work, compile a helper-local proof that exercises every SQLite API below against the exact pinned binding version and build tags. If any API is missing from the public binding, add a narrow internal cgo file in the helper package rather than changing the protocol or weakening enforcement.
- Authorizer: must call `sqlite3_set_authorizer` or an equivalent driver hook on the active SQLite connection.
- Query timeout/cancel: must call `sqlite3_interrupt` for safe cancellation, or the documented fallback is connection-fatal timeout with helper termination.
- Extended result codes: must enable and report extended result codes.
- Single-statement enforcement: must validate the prepare tail and reject non-whitespace trailing SQL.
- Limits: must use `sqlite3_limit` for variable count and relevant size limits where SQLite exposes them.
- Extension loading: must disable extension loading for every connection and keep `load_extension` denied by authorizer policy.
- Version and compile options: must expose SQLite version and compile options in diagnostics/handshake data.
- WAL behavior: supported targets must be tested with WAL databases for readonly open, read/write open, busy/locked behavior, sidecar permissions, long read transactions, writes, and checkpoint interaction. WAL support is required for the CLI MVP on supported Linux targets.

## Protocol Contract

- CLI protocol: backend uses portable `sqlite3` CLI CSV output with `.headers on`, `.mode csv`, and an explicit null sentinel. This supports older SQLite CLI versions that do not provide `.mode json`.
- Helper frame limit: default `maxFrameBytes=1048576`; helper peers negotiate limits in `hello`/`helloAck`; limits may be configured downward and only raised by explicit configuration. CLI mode uses `cli.maxBufferedResultBytes` to bound buffered result output.
- Error schema: CLI stderr/stdout failures are mapped to sanitized JDBC errors; extended SQLite codes may be unavailable unless the backend implements them.
- Result buffering: CLI mode currently buffers each sqlite3 result before exposing cursor batches and enforces `cli.maxBufferedResultBytes`; do not claim true streaming until it is implemented and tested.
- Lifecycle: one JDBC connection owns one SSH exec channel, one `sqlite3` process, and one SQLite connection. stdin/stdout stay open until `Connection.close()` or fatal failure where the CLI remains usable. Idle validation uses a lightweight SQLite query; SSH keepalive is enabled separately.
- Pooling contract: built-in pooling is explicit through `SshSqliteDataSource` with `pool.enabled=true`, partitioned by full security context, and capped at `pool.maxSize=5` by default. Pooled connections must be validated, reset, rolled back on return, and evicted when broken.

## SQL And JDBC Semantics

- Read/write mode: `readonly=true` opens the CLI with `-readonly`, sets `PRAGMA query_only=ON` where supported, rejects writes in JDBC update paths, and relies on SQLite for enforcement on statements the driver cannot classify safely. `readonly=false` runs `sqlite3` without `-readonly` and allows normal DB-tool SQL through stdin. Strong authorizer enforcement requires an optional helper or another direct SQLite API path.
- Standard JDBC parameters: `PreparedStatement` values are rendered as CLI-safe SQL literals for text, NULL, numbers, booleans, dates/times, and BLOBs. Values must never be shell-interpolated.
- SQLite named placeholders: `?`, numbered `?NNN`, and SQLite named placeholders using `:`, `@`, or `$` prefixes are supported through the same ordered JDBC setter values and safe literal rendering. There is no separate public `namedParams` protocol for normal JDBC use.
- SQLite shell `.param`: disabled. `.open`, `.read`, `.shell`, `.system`, `.load`, and arbitrary dot commands remain unsupported as user input.
- Multi-statement SQL: normal scripts are valid DB-tool usage and are passed to sqlite3 stdin. Dot commands remain blocked because they are shell commands, not SQL.
- JDBC adapter strategy: implement generated base adapters for all targeted Java 11 `java.sql` methods first; concrete classes override only supported behavior. Unsupported methods throw documented JDBC exceptions, and reflection tests must prevent `AbstractMethodError`.

## Minimum Support Matrix

- Java: driver builds and runs with Java 11+, compiled with `--release 11` for desktop-tool runtimes that still embed Java 11.
- Client OS: any Java 11 platform that passes the transport test suite; release smoke evidence is required for Linux, macOS, and Windows clients before claiming support.
- Server requirement for CLI MVP: SSH server with a compatible `sqlite3` CLI.
- SQLite CLI: tested `sqlite3` versions and required output-mode support must appear in release diagnostics.
