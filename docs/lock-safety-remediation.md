# Lock-Safety Remediation Plan

This project intentionally allows normal SQLite scripting in `readonly=false` mode. Lock safety therefore means the driver must preserve SQLite's native file-locking model and must not leave a remote `sqlite3` process holding locks after JDBC loses the connection.

## Required Fixes

1. Deterministic sqlite3 cleanup
   - On normal close, close sqlite3 stdin first so the CLI exits by EOF.
   - Wait for process/channel exit for a bounded time.
   - Force-close the local process or SSH exec channel/session if sqlite3 does not exit.
   - On timeout, EOF, protocol parse failure, or broken connection, immediately abort the sqlite3 backend and mark the JDBC connection broken.

2. Reliable sqlite3 error detection
   - Do not rely on asynchronous stderr observation to decide whether a statement succeeded.
   - Route sqlite3 stderr into the same response stream for each statement, or otherwise capture per-statement errors synchronously.
   - Failed writes, commits, and rollbacks must not be reported as success.

3. Lock-contention diagnostics
   - Detect common SQLite busy/locked messages from the CLI.
   - Surface them as lock-contention SQL errors with SQLState `HYT00` for timeout/busy waits or `HY000` with a clear message for immediate locked failures.
   - Do not retry mutating statements after an unknown outcome.

4. WAL and sidecar coverage
   - Test WAL-mode reads and writes through the CLI backend.
   - Test concurrent reader/writer and writer/writer contention behavior.
   - Test permission and sidecar assumptions for `-wal`, `-shm`, rollback journals, and temp files where feasible in local fixtures.

5. CLI-backed pooling and timeout coverage
   - Pooling tests must exercise the canonical sqlite3 CLI backend, not only legacy helper mode.
   - Tests must prove active transactions are rolled back before pooled connection reuse.
   - Tests must prove timed-out or broken CLI connections are evicted and do not keep sqlite3 processes alive.

6. Large-result behavior
   - The CLI backend must not pretend `fetchSize` is network streaming if it has already buffered the whole result.
   - Either implement streaming CSV parsing from sqlite3 stdout into bounded JDBC batches, or enforce explicit result-size limits and document that CLI mode buffers within those limits.
   - Early result-set close must stop or finish the active sqlite3 statement without leaving locks behind.

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
