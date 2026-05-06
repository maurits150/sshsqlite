# Implementation Plan

## Milestone Definitions

Do not use one overloaded meaning of MVP.

| Milestone | Purpose | Production claim |
| --- | --- | --- |
| Technical preview | Java transport can run remote `sqlite3` CLI and bounded queries against disposable databases | No production claim |
| Tool beta | DBeaver can connect, browse metadata, and run bounded read/write SQL against disposable databases | Beta only; no live database claim |
| Read/write CLI MVP | SSHSQLite is installable, observable, packaged, soak-tested, and DBeaver-tested for `sqlite3` CLI read/write mode | Experimental CLI backend; live writes require operator backup discipline |
| Pooling release | Built-in pool with security-context partitioning and taint eviction | Not part of MVP |

Read/write CLI MVP excludes true streaming, updatable result sets, batch execution unless tested, BLOB streaming, cancellation unless a safe control path exists, and built-in pooling. Normal read/write mode must still allow SQLite DDL, PRAGMAs, transactions, maintenance commands, and scripts unless a specific SQLite CLI limitation is proven and documented.

## Phase -1: Repository Bootstrap

Create the build and test skeleton before feature work.

Deliverables:

- `settings.gradle.kts`, root `build.gradle.kts`, and Gradle wrapper.
- `driver/` Java 11 JDBC driver module.
- `integration-tests/` Gradle module for SSH/CLI/JDBC integration tests.
- Gradle tasks: `verify`, `verifyIntegration`, `verifyRelease`, `verifySoak`, `verifyTools`.
- Gradle tasks: `generateJdbcMethodSupport`, `checkJdbcMethodSupport`.
- Containerized OpenSSH fixture for integration tests requiring real Unix permissions, host keys, and remote `sqlite3` exec behavior.
- Fast local process transport fixture for protocol tests that do not require SSH.

Acceptance gate:

- `./gradlew verify` runs and fails only because product code is not implemented yet, not because the build skeleton is missing.
- `checkJdbcMethodSupport` passes against the committed Java 11 generated JDBC method matrix and fails on stale output.

## Phase 0: Design Gates

Do not start broad JDBC work until these decisions are locked:

Concrete Phase 0 choices are locked in `docs/design-decisions.md`. If a later implementation changes any locked choice, update that document and rerun the Phase 0 review before continuing feature work.

- SSH library choice and host-key verification behavior.
- Remote command construction strategy.
- `sqlite3.path` default decision and database path handling model.
- CLI output mode choice, limits, and error mapping.
- Safe startup command where the DB path is passed as the SQLite database argument and SQL is sent only on stdin.
- Result buffering/streaming shape and documented CLI result-size limits.
- Persistent stdin/stdout lifecycle and keepalive behavior.
- Future pool contract with maximum size 5 by default.
- Named parameter and optional `.param` compatibility behavior.
- Readonly enforcement model.
- JDBC interface support matrix and adapter strategy.
- Minimum supported Java version, OS, architecture, and SQLite binding.

Acceptance gate:

- A design review confirms no trust-all SSH mode, no shell interpolation, SQL is never on the remote command line, the DB path is safely passed as the SQLite database argument, and CLI output parsing is bounded.
- A JDBC design review confirms all targeted `java.sql` interface methods have explicit support-table entries before implementation begins.

SQLite CLI acceptance gate:

- Selected `sqlite3` versions support the documented output mode: `.headers on`, `.mode csv`, and a fixed `.nullvalue` sentinel.
- Query timeout/cancel is documented as SSH channel close/process destroy unless a true interrupt path is implemented.
- Parameter binding is documented as implemented, emulated, limited, or unsupported for each release phase.
- Readonly behavior is tested with CLI-supported flags/settings and `PRAGMA query_only=ON` where available.
- SQLite version diagnostics are available.
- WAL behavior is tested on supported server targets.

Transport acceptance gate:

- Release startup safely constructs the `sqlite3` command with the database path as one argument.
- Tests prove SQL and parameter values are sent only on stdin, never on the SSH command line.

## Automation-First Build Plan

Lock-safety remediation is tracked in `docs/lock-safety-remediation.md`. The canonical CLI backend must complete that plan before any release-safety or desktop-tool claim.

Developers should not manually test every phase. Every non-GUI gate must be automated and runnable locally before it becomes a release requirement.

## Tooling Decision

Use Gradle as the top-level build system.

Rationale:

- JDBC drivers are Java artifacts, and Gradle is a standard Java ecosystem build tool for libraries, generated sources, test fixtures, integration tests, signing, publishing, and CI orchestration.
- The Java driver is the primary deliverable consumed by DBeaver and Java applications; other desktop tools require separate evidence before being documented as supported.
- Gradle can run Java unit tests, JDBC reflection tests, generated documentation checks, packaging, signing, and publication from one entry point.
- The canonical deliverable is the Java JDBC driver that runs the remote `sqlite3` CLI; normal use must not require a custom server-side helper.
- Maven is also standard for Java libraries, but Gradle is the better fit here because this project needs generated JDBC matrices, integration-test orchestration, release gates, and packaging checks.
- Make is not the canonical build interface for this project. It may be familiar for shell orchestration, but it is not the right primary tool for a JDBC driver artifact.

Required repository shape:

```text
settings.gradle.kts
build.gradle.kts
driver/                 Java JDBC driver
integration-tests/      SSH/CLI/JDBC integration tests
docs/                   Design and generated support matrices
```

Gradle tasks own orchestration for the Java driver, packaging, and verification workflow.

Required command structure uses Gradle as the Java-facing entry point:

```text
./gradlew verify              fast local verification for normal development
./gradlew verifyIntegration   sqlite3 CLI, SSH test server, JDBC integration tests
./gradlew verifyRelease       full release gate excluding long soak by default
./gradlew verifySoak          long-running reliability and resource-leak tests
./gradlew verifyTools         DBeaver smoke harness or guided evidence capture
```

Gradle is the canonical developer and CI interface.

Automation requirements:

- `./gradlew verify` runs formatting, static checks, Java unit tests, protocol golden tests, generated JDBC matrix freshness checks, and no-secret redaction tests.
- `./gradlew verifyIntegration` starts an automated SSH test server/container with pinned host keys, a trusted `sqlite3` CLI, and a disposable SQLite database.
- `./gradlew verifyIntegration` covers host-key success/failure, CLI startup, safe DB path command construction, readonly rejection, result parsing, process EOF, and lock contention.
- `./gradlew verifyRelease` verifies manifests/provenance where available for driver artifacts, CLI compatibility evidence, install guidance, and JDBC reflection coverage for the read/write CLI MVP. Write-safety verification may be explicitly enabled with `-PverifyWriteRelease=true` and additionally rehearses backup/restore on a disposable WAL database through `verifyWriteReleaseBackupRestore`.
- `./gradlew verifySoak` runs the numeric soak criteria from `docs/operations.md` and emits machine-readable results.
- Fault injection must be automated by release scope: CLI MVP covers `sqlite3` process crash, SSH EOF, stdout write stall, unparsable output, timeout, busy/locked database, failed commit, and unknown-outcome mutation; pooling release adds pool taint eviction.
- Tests must create disposable databases and temporary SSH keys/known-hosts files. They must not require a developer's real SSH account or real database.
- Manual evidence is allowed only for pinned DBeaver GUI workflows that cannot be automated through a stable CLI/API. Those workflows still need a guided script/checklist and captured artifacts. Other desktop tools can be added after separate evidence exists.

CI requirements:

- Pull requests run `./gradlew verify` and a bounded subset of `./gradlew verifyIntegration`.
- Main branch runs full `./gradlew verifyIntegration`.
- Release candidates run `./gradlew verifyRelease` and `./gradlew verifySoak`.
- Release is blocked if any non-GUI gate lacks automation.
- GUI tool smoke checks must attach evidence: tool version, redacted logs, screenshots or exported diagnostics, and the disposable database fixture used.

Concrete Gradle task contract:

- `generateJdbcMethodSupport` reflects the pinned Java 11 target and writes `docs/jdbc-method-support.generated.md`.
- `checkJdbcMethodSupport` fails on placeholder content, stale generated output, target-JDK mismatch, missing method rows, missing behavior, missing failure policy, missing phase, or missing test owner.
- `verifyTools` validates a structured evidence bundle when GUI automation is unavailable. It does not pretend to automate the GUI unless a stable tool automation harness exists.
- `verifyTools` evidence bundle contains tool name/version/build, driver artifact hash, disposable fixture hash, redacted logs, screenshots or exported diagnostics, workflow pass/fail JSON, and unexpected JDBC method trace output.
- `verifyRelease` consumes already-built candidate artifacts and verifies them in containerized install fixtures; it does not build mutable artifacts as part of verification.
- `verifyRelease -PverifyWriteRelease=true` fails closed unless `build/reports/write-release/backup-restore.json` contains passing disposable backup/restore rehearsal evidence.
- `verifyIntegration` uses containerized OpenSSH fixtures for tests requiring real SSH exec behavior, host keys, and remote `sqlite3` execution.

CI profiles:

- Pull request profile: `verify` plus smoke subset of `verifyIntegration`, target under `20` minutes.
- Main profile: full `verifyIntegration` on Linux amd64.
- Nightly profile: `verifySoak` short profile plus large fixture tests.
- Release profile: full `verifyRelease`, full `verifySoak`, signed provenance verification, and tool evidence validation.

## Phase 0b: JDBC Interface Contract

Complete the JDBC interface contract before transport-heavy implementation.

Deliverables:

- Base adapter classes for every targeted `java.sql` interface.
- Method support tables for `Driver`, `Connection`, `Statement`, `PreparedStatement`, `ResultSet`, `ResultSetMetaData`, `DatabaseMetaData`, and `Wrapper` behavior.
- Generated exhaustive support table in `docs/jdbc-method-support.generated.md` from the target Java 11 interfaces.
- Java 11 `--release 11` compile target decision documented.
- Closed-object behavior tests.
- Unsupported method behavior tests.
- Reflection tests proving no implemented JDBC class can throw `AbstractMethodError` for target Java interface methods.

Acceptance gate:

- Every public JDBC method has documented behavior in the generated support matrix and at least smoke-test coverage for supported, unsupported, closed, or invalid-state behavior.

## Phase 1: CLI Backend Prototype

Build the remote `sqlite3` CLI backend first with local-process and SSH stdin/stdout testing.

Deliverables:

- Safe `sqlite3` startup using `sqlite3.path` and the remote database path argument.
- Controlled CLI setup commands for output mode, headers/null handling, busy timeout, and diagnostics.
- Persistent stdin/stdout loop that handles many statements before EOF where practical.
- Startup validation for SQLite version and selected output mode.
- Bounded parser for controlled CSV output with headers and explicit null sentinel.
- SQLite read-only behavior through `-readonly` and CLI-supported settings when `readonly=true`, plus write behavior without `-readonly` when `readonly=false`.
- Busy timeout setting.
- Result handling with bounded memory and early close cleanup by process/channel teardown if needed.
- Structured JDBC errors from CLI exit status, stderr, and parse failures.
- Driver-controlled dot-command setup only; user SQL must not accept arbitrary SQLite shell dot commands.
- Multi-statement script support for normal database-tool usage, or a clearly documented technical-preview limitation until support lands.
- Honest documentation of limitations where CLI mode cannot expose authorizers, extended codes, true binding, true streaming, or true interrupt.

Tests:

- CLI output parser fixtures.
- Malformed and oversized output rejection.
- Query and update execution against temporary SQLite DBs.
- Readonly write rejection.
- Safe command construction for database paths with spaces, quotes, and metacharacters.
- Busy timeout behavior with two SQLite connections.

Acceptance gate:

- CLI backend can run locally under tests without SSH, then over SSH, and never sends SQL on the command line.

## Phase 2: Java Transport

Implement SSH connection and `sqlite3` CLI transport before JDBC surface area expands.

Deliverables:

- JDBC URL parser.
- Connection property parser and redaction.
- SSH host-key verification using OpenSSH known hosts.
- SSH authentication through agent/key/password according to policy.
- Safe remote `sqlite3` command construction with the DB path as the SQLite database argument and no SQL on the command line.
- CLI startup timeout and validation.
- CLI stdout parser with max output enforcement.
- CLI stderr capture.
- Broken-connection detection.

Tests:

- URL parsing edge cases.
- Host-key verification success/failure using test SSH server or fixtures.
- Command escaping tests for spaces, quotes, and metacharacters.
- Tests proving DB paths are safely escaped as command arguments and SQL is sent only through stdin.
- CLI startup failure diagnostics.
- Unsupported CLI output mode rejection.
- Repeated requests over the same `sqlite3` stdin/stdout streams where supported.

Acceptance gate:

- Java code can start `sqlite3` over SSH, run repeated validation/query requests over stdin/stdout, and fail closed on host-key or CLI startup problems.

## Phase 3: Minimal JDBC Query Path

Implement the smallest useful JDBC flow.

Deliverables:

- `Driver.connect()`.
- `Connection.createStatement()`.
- `Statement.executeQuery()`.
- Read-only `Statement.execute()` result-state behavior.
- Forward-only `ResultSet` backed by bounded buffered rows or cursor/fetch row batches; do not claim true streaming unless implemented.
- Basic `ResultSetMetaData`.
- `Connection.close()` lifecycle cleanup.

Tests:

- Java integration test connects and selects rows.
- `Statement.execute()` returns `true` for row-returning SQL and maintains `getResultSet()`/`getUpdateCount()` state.
- Nulls, integers, reals, text, and BLOB values map correctly.
- `setMaxRows()` and fetch size behavior.
- Closing connection closes stdin and terminates the remote `sqlite3` process/SSH channel if needed.
- Closing result sets releases buffered state and, where streaming is later implemented, stops or finalizes the active remote statement safely.
- `sqlite3` process exit or SSH EOF marks the connection broken.

Acceptance gate:

- A plain Java program can connect over SSH and run bounded SELECT queries without unbounded buffering.

This is a technical preview gate, not a production or desktop-tool claim.

## Phase 3b: Minimum Metadata For Tools

Implement metadata before write support. Desktop tools usually call metadata before they allow useful querying.

Deliverables:

- Conservative `DatabaseMetaData` capability methods needed by DBeaver, with other desktop-tool behavior added only after evidence exists.
- Correctly shaped metadata result sets for tables, columns, primary keys, indexes, foreign keys, schemas, catalogs, table types, type info, and empty unsupported procedure/function/UDT results.
- SQLite introspection using SQLite 3.27-compatible `sqlite_master` and schema-qualified PRAGMA statements with quoted identifiers/literals.
- Metadata browsing that uses side-effect-free SQLite 3.27-compatible introspection and preserves schema-qualified identifiers.
- Tool trace capture for unexpected JDBC calls.

Tests:

- JDBC metadata result-set shape tests with required JDBC columns and ordering.
- Metadata tests for weird identifiers, generated columns, hidden columns, views, virtual tables, foreign keys, indexes, `WITHOUT ROWID`, and internal `sqlite_%` tables.
- DBeaver read-only browse/query smoke evidence on disposable fixtures.

Acceptance gate:

- Read-only tool beta can browse schemas/tables/columns and run bounded read-only SQL in the pinned DBeaver workflow.

## Phase 4: Updates And Transactions

Add write support carefully as part of the read/write CLI MVP. CLI-mode prepared statements must use tested setter behavior and documented literal rendering unless a true binding path exists.

Deliverables:

- `Statement.executeUpdate()`.
- `Statement.execute()` result-state behavior.
- `Connection.setAutoCommit()`.
- Deferred transaction support.
- `commit()` and `rollback()` edge cases.
- Close rollback for active transactions.
- Readonly enforcement across driver and CLI mode.

Tests:

- Autocommit insert/update/delete.
- Manual transaction commit/rollback.
- `commit()` and `rollback()` failures when autocommit is true.
- Failed commit behavior under lock.
- Write rejection in readonly mode.

Acceptance gate:

- Write-capable sessions behave predictably and readonly sessions cannot mutate data.

## Phase 5: Prepared Statements

Prepared statements are required for useful applications. In CLI mode they may render values as carefully escaped SQL literals until a true SQLite binding path exists; this must be documented and tested as CLI literal rendering, not native binding.

Deliverables:

- `Connection.prepareStatement()`.
- 1-based JDBC parameter-index binding for `?`, `?NNN`, `:name`, `@name`, and `$name` placeholders.
- Common setter methods.
- `clearParameters()`.
- Missing parameter validation.
- CLI-safe SQL literal rendering for supported setters, or true prepare/bind/step/finalize if a future non-CLI backend is explicitly retained.
- Optional exact-name binding only after standard JDBC parameter-index behavior works and a backend can prove true named binding.
- Optional limited `.param` preprocessor deferred until explicitly needed by tools/users.

Tests:

- All supported setter mappings.
- Missing parameter failures.
- SQL injection regression tests proving values are escaped as SQL literals in CLI mode and never shell-interpolated.
- Repeated execution with changed parameters.
- SQLite parameter-index binding for named placeholders and repeated names.
- Optional exact-name binding validation against SQLite parameter metadata.
- Optional `.param` preprocessor parsing and redaction.

Acceptance gate:

- Application code can safely use documented positional and named placeholder behavior for query and update operations, with CLI-mode literal-rendering limitations clearly stated.

## Phase 5b: Future Pooling (Not MVP)

Built-in pooling is deferred until after physical connection lifecycle, read-only tool support, and write unknown-outcome handling are stable.

Deliverables:

- `SshSqliteDataSource` or documented integration with an external pool after MVP.
- Built-in pool maximum of 5 physical CLI-backed connections for the first pooling release.
- Pool partitioning by full security context and immutable open options.
- Ping validation before reuse.
- Broken connection eviction.
- Rollback, open statement close, and state reset on return to pool.
- Bounded pool shutdown that closes all `sqlite3` processes and SSH sessions.

Tests:

- Borrow and return connections repeatedly.
- Evict physical connection after simulated SSH EOF or `sqlite3` process exit.
- Validate idle connection with `ping`.
- Ensure active transactions rollback on return.
- Ensure reset failure evicts the physical connection.
- Run five concurrent read connections against the same database.
- Verify read-write physical connections are never reused for readonly borrowers.
- Verify write contention surfaces as SQLite busy/locked rather than pool failure.

Acceptance gate:

- Pooling reuses healthy CLI-backed connections without masking broken streams or SQLite writer limits.
- This gate is not required for the CLI MVP.

## Phase 6: Metadata For Tools

Expand metadata iteratively against real desktop tools after the minimum Phase 3b metadata gate.

Deliverables:

- `DatabaseMetaData.getTables()`.
- `getColumns()`.
- `getPrimaryKeys()`.
- `getIndexInfo()`.
- `getImportedKeys()`.
- `getTypeInfo()`.
- `getSchemas()` and `getCatalogs()`.
- `getTableTypes()`.
- Identifier quote and capability methods.
- Tool-specific compatibility notes where behavior differs.

Tests:

- Unit tests for metadata SQL and identifier quoting.
- JDBC metadata result-set shape tests with required JDBC columns.
- Complete `docs/jdbc-metadata-resultsets.md` before read-only tool beta.
- Integration tests against SQLite schemas with views, indexes, generated columns, foreign keys, and weird identifiers.
- DBeaver table browsing smoke test.
- Optional additional desktop-tool browsing smoke test only after that tool is intentionally targeted.

Acceptance gate:

- DBeaver can connect, list schemas/tables/columns, open data grids, and run custom SQL. Other desktop tools require their own evidence before support is documented.
- Metadata methods return conservative values or correctly shaped empty result sets instead of surprising tool-breaking exceptions.
- `docs/jdbc-metadata-resultsets.md` defines concrete JDBC metadata result-set shapes and is enforced by tests.

## Phase 7: Timeouts, Cancellation, And Hardening

Deliverables:

- `Statement.setQueryTimeout()`.
- `Statement.cancel()` if a safe control path is implemented.
- SSH channel/process termination fallback for CLI mode; `sqlite3_interrupt()` only if a future direct SQLite backend is explicitly retained.
- `SQLFeatureNotSupportedException` for `Statement.cancel()` until a safe control path exists.
- SSH keepalive configuration.
- Query deadline propagation.
- Bounded stderr buffer.
- Operational diagnostics.

Tests:

- Long-running query timeout.
- User cancellation.
- Transport timeout.
- CPU-heavy query interruption.
- Connection state after cancellation.

Acceptance gate:

- Users can stop runaway work and the driver reports whether the connection remains usable.

## Phase 8: Packaging And Release

Deliverables:

- Java driver artifact.
- Installation docs.
- Version compatibility matrix.
- Example DBeaver configuration.
- Release smoke checklist in `docs/release-smoke.md` plus `generateToolEvidenceTemplate`/`verifyTools` evidence validation for GUI workflows.

Acceptance gate:

- A fresh machine can install the driver jar, verify host keys, connect read-only, browse a disposable database, run bounded queries, prove read-only rejection, and capture diagnostics using only documented steps.

## Production Readiness Checklist

- Host-key verification fails closed.
- Passwords and passphrases are never accepted in URLs.
- `sqlite3.path` is deterministic and safely executed.
- Remote `sqlite3` binary trust is documented as server operator responsibility.
- Database path is safely passed as the SQLite database argument; normal CLI mode relies on SSH account filesystem permissions rather than helper allowlists.
- Readonly mode passes `-readonly`, uses `PRAGMA query_only=ON` where available, and rejects driver-detected mutations where possible.
- Extension loading is not enabled by the driver, and user SQL cannot invoke arbitrary sqlite3 shell dot commands such as `.load`, `.shell`, `.system`, `.read`, or `.open`.
- `ATTACH`, `VACUUM`, PRAGMAs, DDL, and transaction scripts remain normal SQLite operations in read/write mode unless a proven CLI/backend limitation is documented and tested.
- Protocol frames have hard size limits.
- Response IDs, operation families, stale frames, duplicate frames, and out-of-order frames are validated and fail closed.
- Mutating SQL, commit, rollback, open, fetch, and cursor-close operations are never retried after request write completion unless explicitly documented idempotent.
- SQL is sent through stdin, not SSH command arguments.
- Result sets use cursor/fetch flow control with bounded batches.
- `sqlite3` stdin/stdout streams stay open across repeated requests where the CLI protocol remains usable.
- Idle connections are validated with `ping` and SSH keepalive.
- Future built-in pooling supports at most 5 physical CLI-backed connections for the first pooling release and evicts broken processes.
- Future pooling is partitioned by security context and immutable open options.
- Standard JDBC setters use documented CLI-safe SQL literal rendering by parameter index until a true binding backend exists; optional `namedParams` and limited `.param` preprocessing remain future work.
- Optional limited `.param` preprocessing strips shell meta-commands and sends `namedParams`.
- Query timeout and cancellation behavior is documented and tested.
- Transaction edge cases match JDBC expectations.
- Unknown-outcome mutations mark connections broken and non-poolable.
- Read/write mode is the normal default and supports normal database-tool SQL, including DDL and maintenance operations, subject to SQLite permissions and CLI limitations.
- Live write use requires operator backup discipline, but basic CLI read/write setup must work with `readonly=false` and must not require helper integrity properties or helper installation.
- SQLite busy/locked/corrupt/I/O errors map clearly to JDBC errors.
- DBeaver metadata workflows are tested; other desktop tools require separate evidence before support is documented.
- JDBC interface reflection coverage proves no `AbstractMethodError`, `NoSuchMethodError`, or accidental `UnsupportedOperationException` path remains.
- Generated JDBC method-support matrix exists and is fresh for the target Java release.
- JDBC metadata result-set shape document exists and is enforced by tests.
- Logs redact secrets, SQL parameters, and sensitive paths by default.
- sqlite3 stderr/stdout diagnostics cannot corrupt result parsing without a clear parse/startup error.
- sqlite3 process exit or SSH channel failure marks the connection broken.
- Close rolls back active transactions where possible.
- Release artifacts document Java runtime, client platform evidence, SSH behavior, and remote SQLite CLI compatibility.

## MVP Reliability Gate

MVP is reliable enough for a documented experimental read-only claim only when all of these are true:

- Fresh install succeeds from documented artifacts on every supported server target.
- Driver/remote-`sqlite3` compatibility matrix is published and version diagnostics are available at connection startup.
- Release artifacts have checksum evidence, documented trusted release identity when publishing outside the repo, provenance or attestation where available, and dependency inventory.
- A readonly DBeaver smoke test passes on the pinned supported version.
- Large result browsing proves bounded memory through enforced CLI result limits or true cursor/fetch batching.
- Repeated connect/query/close soak passes without leaked `sqlite3` processes or stuck SSH sessions.
- Soak passes the applicable non-pooling numeric criteria in `docs/operations.md` for iteration count, duration, concurrent non-pooled physical connections, and resource ceilings.
- Long idle connection validation passes with protocol ping and SSH keepalive.
- sqlite3 process exit, SSH EOF, malformed CLI output, and query timeout mark connection state correctly.
- Logs and diagnostic bundles are redaction-tested.

Write-capable release is reliable enough for a documented experimental write claim only when these additional gates pass:

- Backup and restore runbook has been rehearsed against a disposable restored copy.
- `PRAGMA integrity_check` passes on a restored backup.
- Write, commit, rollback, readonly rejection, busy/locked, failed commit, and unknown-outcome behavior are tested.
- DBeaver edit workflows pass only on disposable test databases before use on live data.
- Pooling, if enabled, evicts broken or tainted CLI-backed connections and never reuses read-write physical connections for readonly borrowers.
- Upgrade and rollback runbooks are tested with driver versions and remote `sqlite3` versions/paths.

Release must be blocked if any non-GUI gate has no automated test. Manual evidence is accepted only for pinned DBeaver GUI workflows that cannot be automated, and explicit deferral is accepted only when the feature is marked unsupported for the release claim.

## Expected Effort

Rough estimate for a careful implementation:

```text
CLI protocol prototype:                 1-2 days
SSH transport and safe startup:         2-4 days
Minimal JDBC SELECT path:               2-4 days
Writes and transactions:                2-4 days
Prepared statements and type mapping:   2-4 days
DBeaver metadata support:               1-2 weeks
Timeout/cancel/hardening:               3-7 days
Packaging and release docs:             2-4 days
Robust experimental driver:             several weeks
```

The schedule assumes iterative testing against real desktop tools, because database tools often rely on JDBC metadata methods that are not obvious from basic examples.
