# Lock-Safety Remediation Plan

This project intentionally allows normal SQLite scripting in `readonly=false` mode. Lock safety therefore means the driver must preserve SQLite's native file-locking model and must not leave a remote `sqlite3` process holding locks after JDBC loses the connection.

## Current Status

Reviewed against current code and `AGENTS.md` on 2026-05-06.

### Complete

1. Deterministic sqlite3 cleanup on normal close
   - `LocalProcessTransport.close()` closes sqlite3 stdin, waits up to 2 seconds, then force-kills the process.
   - `SshTransport.close()` closes the protocol reader, then closes the SSH exec channel/session gracefully with forced close fallback.
   - `SshSqliteConnection.close()` rolls back an active JDBC transaction before closing the transport when the backend is still healthy.

2. Fatal cleanup for unknown backend state
   - `CliProtocolClient` marks the connection broken and aborts the backend on query timeout, interrupted waits, EOF while waiting for a marker, oversized buffered output, and CLI output parse failure.
   - Local-process CLI tests cover timeout process termination, oversized-result process termination, and parse-failure process termination.

3. Busy timeout configuration and lock-contention diagnostics
   - CLI setup sends `.timeout <busyTimeoutMs>` before user statements.
   - Common busy/locked messages map to a transient SQL error with SQLState `HYT00`.
   - Timeout paths also surface SQLState `HYT00` and abort the backend rather than retrying an unknown mutating outcome.

4. Bounded buffered CLI results
   - CLI mode enforces `cli.maxBufferedResultBytes` and aborts the backend when the limit is exceeded.
   - The current implementation does not claim true network streaming: JDBC `fetchSize` batches over rows already materialized by the CLI client.
   - The soak report labels bounded large-result coverage as a smoke test, not streaming proof.

5. Basic WAL, contention, and pooling coverage on the canonical CLI backend
   - Unit coverage exercises WAL-mode read/write and concurrent writer contention through local sqlite3 CLI mode.
   - Unit coverage exercises pooled rollback-and-reuse through local sqlite3 CLI mode.
   - Integration coverage still contains legacy-helper pooling tests, but CLI-specific pooling coverage now exists in `CliProtocolClientTest`.

### Partial Or Still Planned

1. Per-statement sqlite3 error detection is improved but still not ideal
   - SSH CLI mode routes stderr into the same stdout stream, which lets sqlite3 error text participate in marker-delimited statement handling.
   - Local CLI mode still captures stderr separately. It can detect captured errors after a statement, but this is less precise than a fully per-statement stderr protocol.
   - Planned: make local and SSH CLI error handling equivalent, or document the remaining local-fixture limitation explicitly.

2. WAL and sidecar coverage is not complete
   - Covered: WAL read/write smoke and writer contention.
   - Still planned: direct tests for `-wal`, `-shm`, rollback journal, and temp-file permission failures where feasible.

3. Pool eviction after timed-out or broken CLI connections needs direct pooled coverage
   - Covered: active transaction rollback before CLI pooled reuse, and non-pooled timeout process termination.
   - Still planned: a pooled CLI test proving timed-out or broken physical connections are evicted and never reused.

4. True streaming remains planned
   - Current CLI mode uses bounded materialization followed by JDBC fetch batches.
   - Planned: stream CSV parsing from sqlite3 stdout into bounded JDBC batches if the project decides to support true large-result streaming.

5. Multi-statement scripting is no longer blocked in the current worktree, but result semantics need more coverage
   - `AGENTS.md` says multi-statement scripts are normal desktop-tool usage.
   - Current CLI statement execution no longer rejects semicolon-separated scripts before sending them to sqlite3.
   - Covered: a write script with DDL plus multiple inserts through `executeUpdate()`.
   - Still planned: explicit behavior and tests for scripts with multiple row-producing statements, mixed row/update statements, and update-count expectations.

## Required Fixes

1. Deterministic sqlite3 cleanup: complete for current CLI transports, with remaining pooled-broken-connection coverage planned.
2. Reliable sqlite3 error detection: partial; SSH CLI is marker-stream aligned, local CLI stderr capture remains less precise.
3. Lock-contention diagnostics: complete for common busy/locked messages and timeout SQLState mapping.
4. WAL and sidecar coverage: partial; WAL smoke/contention exists, sidecar permission coverage remains planned.
5. CLI-backed pooling and timeout coverage: partial; CLI rollback/reuse and timeout process-kill tests exist, pooled timeout/broken eviction remains planned.
6. Large-result behavior: complete for bounded buffering, planned for true streaming.

## Non-Goals

- Do not block normal user SQL, DDL, PRAGMAs, transactions, or maintenance scripts in `readonly=false` mode just to appear safer.
- Do not implement a SQL firewall in CLI mode.
- Do not use SFTP or file copying to access a live database.

## Acceptance Gates

- `./gradlew verify` passes.
- `./gradlew verifyIntegration` passes.
- `./gradlew verifySoak` covers the CLI backend for lifecycle and lock cleanup.
- Local tests prove timeout/broken connections terminate sqlite3 and release locks.
- WAL/concurrency tests prove SQLite returns busy/locked behavior without driver-level corruption or leaked processes.
