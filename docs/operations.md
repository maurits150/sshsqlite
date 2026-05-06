# Operations

SSHSQLite is still experimental and not production safe. This document describes the current CLI-over-SSH operating model and release checks; it is not a production readiness claim.

## JDBC URL Grammar

Basic forms:

```text
jdbc:sshsqlite://user@host/path/to/database.db
jdbc:sshsqlite://user@host:22/path/to/database.db
jdbc:sshsqlite://host/path/to/database.db
```

IPv6 form:

```text
jdbc:sshsqlite://user@[2001:db8::10]:22/path/to/database.db
```

Remote paths are URL paths and must be percent-decoded exactly once.

Examples:

```text
jdbc:sshsqlite://alice@db.example.org/srv/app/app.db?readonly=true
jdbc:sshsqlite://alice@db.example.org/srv/app/my%20db.sqlite?busyTimeoutMs=1000
```

Rules:

- The JDBC prefix is `jdbc:sshsqlite:`.
- URL user, host, port, path, and query parameters are parsed as URI components.
- Percent-encoded path bytes are decoded as UTF-8.
- Remote paths containing spaces, `?`, `#`, `%`, or non-ASCII characters must be percent-encoded.
- Passwords and private key contents are not allowed in URLs.
- Query parameters and connection properties must use the same canonical property names.
- Explicit `Properties` values should override URL query values.
- `db.path` property overrides the URL path when both are present.
- Unknown query parameters should fail fast unless an extension namespace is defined.

## Connection Properties

Core properties:

| Property | Required | Default | Notes |
| --- | --- | --- | --- |
| `ssh.host` | yes | URL host | Remote SSH host |
| `ssh.port` | no | `22` | Remote SSH port |
| `ssh.user` | no | URL user or local user | SSH username |
| `ssh.knownHosts` | no | standard user file | OpenSSH-compatible known hosts |
| `ssh.privateKey` | no | first existing `~/.ssh/id_ed25519`, `id_ecdsa`, `id_rsa`, `id_dsa`, or `identity` | Path to private key, not key contents |
| `ssh.privateKeyPassphrase` | no | none | Connection property only, never URL; passphrase for encrypted `ssh.privateKey` |
| `ssh.password` | no | none | Connection property only, never URL |
| `password` | no | none | Desktop-tool alias for `ssh.password`; accepted from DBeaver credential fields |
| `user`, `username`, `UID` | no | URL user or local user | Desktop-tool aliases for `ssh.user` |
| `pass`, `PWD` | no | none | Desktop-tool aliases for `ssh.password` |
| `privateKey`, `privateKeyFile`, `keyFile`, `identityFile`, `sshKey` | no | none | Desktop-tool aliases for `ssh.privateKey` |
| `privateKeyPassphrase`, `passphrase` | no | none | Desktop-tool aliases for `ssh.privateKeyPassphrase` |
| `knownHosts`, `knownHostsFile` | no | standard user file | Desktop-tool aliases for `ssh.knownHosts` |
| `database` | no | URL path | Desktop-tool alias for `db.path` |
| `sqlite3Path`, `sqlitePath` | no | `/usr/bin/sqlite3` | Desktop-tool aliases for `sqlite3.path` |
| `ssh.agent` | no | `false` | SSH agent auth is disabled in the self-contained driver; use `ssh.privateKey` or `ssh.password` |
| `ssh.connectTimeoutMs` | no | `10000` | TCP/SSH connect timeout |
| `ssh.keepAliveIntervalMs` | no | `30000` | SSH keepalive interval |
| `db.path` | no | URL path | Remote database path; required unless present in the URL |
| `readonly` | no | `false` | Normal read/write mode; set `true` for read-only inspection |
| `writeBackupAcknowledged` | production safety only | `false` | Optional operator confirmation for hardened production write policies; not required for basic read/write CLI setup |
| `adminSql` | no | ignored in CLI mode | Reserved for optional policy/helper backends |
| `allowSchemaChanges` | no | ignored in CLI mode | Reserved for optional policy/helper backends |
| `transactionMode` | no | `deferred` | `deferred`, `immediate`, or `exclusive` for manual transactions |
| `busyTimeoutMs` | no | `1000` | SQLite busy timeout |
| `queryTimeoutMs` | no | `30000` | Default statement timeout |
| `maxRows` | no | statement default | Accepted for compatibility; current query limits come from JDBC statement settings such as `Statement.setMaxRows()` |
| `fetchSize` | no | `200` | Advisory row batch size |
| `sqlite3.path` | no | `/usr/bin/sqlite3` | Remote sqlite3 executable |
| `sqlite3.startupTimeoutMs` | no | `10000` | CLI startup and validation timeout |
| `protocol.pingIntervalMs` | no | `30000` | Idle CLI validation interval |
| `protocol.pingTimeoutMs` | no | `5000` | CLI validation timeout |
| `transport.writeTimeoutMs` | no | `10000` | Timeout for writing CLI stdin |
| `stderr.maxBufferedBytes` | no | `65536` | Recent sanitized CLI stderr retained |
| `cli.maxBufferedResultBytes` | no | `16777216` | Maximum buffered stdout bytes for one sqlite3 CLI statement before aborting the backend |
| `pool.enabled` | future | `false` | Enable future driver `DataSource` pooling |
| `pool.maxSize` | future | `5` | Maximum physical CLI-backed connections supported by the future built-in pooling release |
| `pool.minIdle` | no | `0` | Minimum idle physical connections |
| `pool.idleTimeoutMs` | no | `300000` | Idle eviction timeout |
| `pool.validationTimeoutMs` | no | `5000` | Ping timeout before reusing idle connection |
| `pool.maxLifetimeMs` | no | `1800000` | Maximum physical connection age |
| `param.dotCommands` | reserved | `false` | Accepted property name; arbitrary user dot-command preprocessing is not implemented in CLI mode |
| `log.level` | reserved | `INFO` | Accepted property name for future redacted logging controls |
| `trace.protocol` | reserved | `false` | Accepted property name for future redacted protocol tracing controls |

Security-sensitive values must be redacted from `toString()`, logs, exceptions, and diagnostics.

## SQLite CLI Deployment

MVP production deployment requires a trusted remote `sqlite3` CLI available to the SSH account. No custom `sshsqlite-helper` is required for normal use.

```text
sqlite3.path=/usr/bin/sqlite3
```

The driver starts `sqlite3` with a safely constructed SSH exec command. The JDBC URL path or `db.path` property is passed as the remote SQLite database path argument to `sqlite3`; SQL is sent only on stdin and must not be interpolated into the shell command.

Typical production server layout:

```text
/usr/bin/sqlite3 or another trusted sqlite3.path
/var/log/sshsqlite/ optional, if driver-side diagnostics are collected remotely
```

Permission guidance:

```text
database read-only session       readable by sshsqlite
database write session           readable/writable by sshsqlite
sqlite3 binary and parent dirs   managed by trusted server operator/package manager
```

The SSH login user should not be able to replace the configured `sqlite3.path` binary or its parent directories. Trust in the remote `sqlite3` binary is a server operator responsibility, normally handled by OS package management and host hardening.

CLI command smoke test:

```bash
ssh user@example.com '/usr/bin/sqlite3 --version'
```

The `ssh ... --version` smoke test is only for a fixed trusted command. Do not adapt that shell pattern to include SQL or user-controlled values.

### Release Artifacts And Version Compatibility

Each release must publish:

- Java driver artifact version.
- Tested remote `sqlite3` versions and required CLI output modes.
- Signed checksum manifest using a documented trusted release identity.
- Signed build provenance or signed attestation for driver artifacts.
- SBOM or dependency inventory for release artifacts.
- Pinned Java SSH library versions used by the release.

The driver must fail CLI startup when:

- `sqlite3.path` cannot be executed.
- The selected CLI output mode is unsupported.
- Startup validation cannot prove the process is the expected SQLite CLI shape.

Unsigned manifests, unsigned provenance/attestations, or artifacts without a documented trusted release identity are development-only and must not be used for production releases.

Production signing policy:

- Preferred release signing uses Sigstore `cosign` with a documented OIDC identity and Rekor transparency log entry.
- GPG signatures are acceptable only if the release key fingerprint, owner, rotation policy, and revocation process are published in release documentation.
- Operators must verify release artifacts for the JDBC driver; trust in the remote `sqlite3` binary comes from server package management and host controls.
- Operators must verify signed provenance/attestation before installing driver artifacts.

Example verification workflow:

```bash
./gradlew packageRelease
./gradlew verifyRelease
cosign verify-blob --bundle sshsqlite_SHA256SUMS.bundle --certificate-identity <release-identity> --certificate-oidc-issuer <issuer> sshsqlite_SHA256SUMS
sha256sum -c sshsqlite_SHA256SUMS
cosign verify-attestation --type slsaprovenance --certificate-identity <release-identity> --certificate-oidc-issuer <issuer> <artifact-ref>
```

`verifyRelease` consumes existing files under `build/distributions`; run `packageRelease` first to produce a candidate, then add signed checksum and provenance/attestation evidence before production verification. Unsigned local development candidates without desktop-tool evidence may be checked with `./gradlew verifyRelease -PallowUnsignedRelease=true -PallowMissingToolEvidence=true`, but production verification fails closed without signed manifest, provenance evidence, and valid tool evidence. Optional local signing hooks can be supplied with `-PcosignVerifyManifestCommand=...`, `-PgpgVerifyManifestCommand=...`, `-PcosignVerifyProvenanceCommand=...`, or `-PgpgVerifyProvenanceCommand=...`; hooks are not run unless configured.

Release notes must include a compatibility matrix:

| Driver version | CLI mode | Tested sqlite3 versions | Supported server targets | Notes |
| --- | --- | --- | --- | --- |
| `0.1.x` | CSV with headers and explicit null sentinel | documented per release | SSH server with sqlite3 CLI | MVP CLI backend; optional helper is not required |

Current CLI portability limits:

- Diagnostics are available with `sqlite3 --version` and driver startup validation output.
- The driver uses `.headers on`, `.mode csv`, and a fixed `.nullvalue` sentinel because `.mode json` is unavailable on supported older SQLite CLI versions such as 3.27.2.
- Operators should treat the server package manager and `sqlite3 --version` output as the source of truth for the remote CLI binary.

### Production CLI Install Runbook

This runbook is for the production CLI backend. It supports read-only and read/write connections through the remote `sqlite3` CLI. Claim production readiness only after `packageRelease`, production `verifyRelease`, full `verifySoak`, and `verifyTools` evidence all pass without development bypasses.

Release artifact preparation:

1. Build a local candidate with `./gradlew packageRelease`, or download the published candidate artifacts from the trusted release location.
2. Confirm `build/distributions` includes one self-contained driver jar, `sshsqlite_SHA256SUMS`, `sshsqlite-release-metadata.json`, dependency inventories, signed checksum evidence, and signed provenance or attestation.
3. Verify production release evidence with `./gradlew verifyRelease` against existing `build/distributions` artifacts. Run `./gradlew packageRelease` first only when producing a new mutable candidate; `verifyRelease` itself must not build mutable artifacts.
4. Do not use `-PallowUnsignedRelease=true` or `-PallowMissingToolEvidence=true` for production. Those flags are development/preview bypasses only.
5. If release verification depends on external signing tooling, configure hooks with `-PcosignVerifyManifestCommand=...`, `-PgpgVerifyManifestCommand=...`, `-PcosignVerifyProvenanceCommand=...`, or `-PgpgVerifyProvenanceCommand=...`.

Repository distribution workflow:

1. `./build.sh` runs `./gradlew packageRelease`, then copies the self-contained driver jar and `sshsqlite_SHA256SUMS` into `dist/` for desktop-tool users.
2. The current GitHub Action runs `./build.sh` on Java 11 and uploads `dist/sshsqlite-driver-0.1.0-SNAPSHOT-all.jar` plus `dist/sshsqlite_SHA256SUMS` as the `sshsqlite-jdbc-driver` artifact.
3. Full release metadata and dependency inventories remain under `build/distributions` unless the release process explicitly publishes them from that directory.

Server install steps:

1. Verify `sqlite3` is installed at the configured `sqlite3.path`, for example `/usr/bin/sqlite3`.
2. Ensure the SSH login user cannot replace the `sqlite3` binary or its parent directories.
3. Ensure the SSH account has only the database file permissions needed for the intended readonly or read/write mode.
4. Verify `ssh user@example.com '/usr/bin/sqlite3 --version'` reports a tested SQLite CLI version.
5. Pin the server host key in an OpenSSH-compatible `known_hosts` file and verify the fingerprint out of band.

Client configuration steps:

1. Configure the driver jar in DBeaver or another Java application. DBeaver is the only documented desktop tool for now.
2. Use a JDBC URL such as `jdbc:sshsqlite://sshsqlite@example.com:22/srv/app/data.db` for normal read/write access, or add `readonly=true` for a read-only connection.
3. Supply `ssh.knownHosts`, one SSH authentication method, `sqlite3.path=/usr/bin/sqlite3`, and the intended `readonly` value as connection properties.
4. Keep passwords and private key passphrases in application or desktop-tool credential storage. Never put them in the URL.
5. Confirm desktop tools use property fields for `sqlite3.path` and SSH settings, and capture diagnostics for the release evidence bundle.
6. Run the release smoke checklist in `docs/release-smoke.md` before declaring the installation ready for read-only use.

DBeaver setup details:

1. Add `dist/sshsqlite-driver-0.1.0-SNAPSHOT-all.jar` as a custom driver library.
2. Set the driver class to `org.sshsqlite.jdbc.SshSqliteDriver`.
3. Use a URL template such as `jdbc:sshsqlite://{host}:{port}/{database}` with default port `22`, or paste the full JDBC URL.
4. Put SSH credentials, `ssh.knownHosts`, `sqlite3.path`, and `readonly` in DBeaver driver properties or credential fields, not in the URL.

### SQLite CLI Verification Runbook

Before first use and after server SQLite upgrades:

1. Verify the signed driver checksum manifest and provenance/attestation.
2. Confirm `sqlite3.path` points to the intended server binary.
3. Confirm the SSH login user cannot replace that binary or its parent directories.
4. Confirm `ssh user@example.com '/usr/bin/sqlite3 --version'` reports a tested version.
5. Run the release smoke checklist against a disposable or readonly database.

### Upgrade And Rollback Runbook

Driver or server SQLite upgrades must be explicit and reversible:

1. Confirm the target driver and remote `sqlite3` version appear in the release compatibility matrix.
2. Confirm signed checksum and provenance/attestation evidence for the new artifacts.
3. Record the currently deployed tuple: driver version, `sqlite3.path`, SQLite CLI version, server target, database path, and readonly mode.
4. Upgrade the driver or server SQLite package through normal deployment tooling.
5. Verify executable path, parent directory ownership, version, and diagnostics for `sqlite3`.
6. Drain or close existing physical connections so no old `sqlite3` process remains borrowed. Built-in pooling is not part of the CLI MVP, but external pools must still be drained.
8. Connect with the intended `readonly` mode and run the release smoke checklist in `docs/release-smoke.md`.
8. If smoke tests fail, restore the previous driver or server SQLite package, close all failed connections, and rerun the smoke checklist.
9. Record the final deployed tuple and smoke-test result.

Rollback checklist:

1. Confirm the previous driver and `sqlite3` package/path are available.
2. Restore client or deployment configuration to the previous driver and `sqlite3.path` as needed.
3. Close every connection created with the failed version. If an external pool is used, evict all physical connections for that security context.
4. Run `sqlite3 --version`, known-host verification, read-only connect, metadata browse, bounded `SELECT`, read-only rejection, and diagnostics bundle capture.
5. Keep failed-version logs for investigation, but do not leave clients configured to use them.

## Recommended Defaults

Production defaults should be conservative:

| Setting | Recommended default |
| --- | --- |
| `readonly` | `false` |
| `writeBackupAcknowledged` | `false` |
| `adminSql` | ignored in CLI mode |
| `allowSchemaChanges` | ignored in CLI mode |
| `busyTimeoutMs` | `1000` |
| `queryTimeoutMs` | `30000` |
| `ssh.connectTimeoutMs` | `10000` |
| `ssh.keepAliveIntervalMs` | `30000` |
| `sqlite3.startupTimeoutMs` | `10000` |
| `protocol.pingIntervalMs` | `30000` |
| `protocol.pingTimeoutMs` | `5000` |
| `stderr.maxBufferedBytes` | `65536` |
| `maxFrameBytes` | `1048576` |
| `cli.maxBufferedResultBytes` | `16777216` |
| `fetchSize` | `200` |
| `trace.protocol` | `false` |
| `pool.enabled` | `false` |
| `pool.maxSize` | `5` |
| `param.dotCommands` | `false` |

Write sessions are opt-in by setting `readonly=false`:

```text
readonly=false
transactionMode=deferred
busyTimeoutMs=1000
queryTimeoutMs=30000
```

Production deployments should require a SQLite-consistent backup and restore procedure before important live write use. A hardened production profile may require `writeBackupAcknowledged=true`, but normal DBeaver read/write setup should work with `readonly=false` and no helper properties.

## Observability And Diagnostics

The driver should log enough to debug failures without exposing data.

Driver log fields:

- Connection ID.
- Host alias, not necessarily full host if sensitive.
- SQLite CLI version after startup validation.
- Protocol version.
- Request ID.
- Operation name.
- Duration.
- Row count or update count.
- SQLite code and SQLState on error.
- Whether the connection was marked broken.

Fields redacted by default:

- Passwords and passphrases.
- Private key contents.
- SQL parameter values.
- Full SQL text.
- Full remote paths in user-facing errors.

CLI stderr policy:

- Capture stderr separately from query/result stdout.
- Bound stderr buffering to avoid memory growth.
- Include recent sanitized stderr in startup failures.
- Retain the last `stderr.maxBufferedBytes` bytes per `sqlite3` process by default.
- Do not parse stderr as result data.

Clear diagnostic messages are required for:

- Unknown or changed SSH host key.
- SSH authentication failure.
- `sqlite3.path` missing or not executable.
- Operator-identified unsafe `sqlite3.path` or executable directory permissions.
- Unsupported SQLite CLI version or output mode.
- Database path not accessible to the SSH account.
- Database file missing or permission denied.
- Readonly write rejection.
- SQLite locked/busy timeout.
- Query timeout/cancellation.
- Unparsable CLI output.
- `sqlite3` crash or SSH EOF.
- Stale pooled connection eviction.
- `.param` parse failure when dot-command compatibility is enabled.

Required structured events:

| Event | Required fields |
| --- | --- |
| `connection.start` | connection ID, host alias, driver version |
| `sqlite3.start` | connection ID, sqlite3 path, version, output mode |
| `db.open` | connection ID, readonly, WAL detected |
| `statement.finish` | connection ID, request ID, operation, duration, row/update count, truncated |
| `statement.error` | connection ID, request ID, SQLState, SQLite code, retryable, connection broken |
| `connection.broken` | connection ID, reason, last request ID |
| `pool.evict` | pool key hash, reason |

Recommended metrics:

| Metric | Meaning |
| --- | --- |
| `sshsqlite.connections.open` | Current open physical connections |
| `sshsqlite.connections.broken_total` | Broken connections by reason |
| `sshsqlite.sqlite3.startup_ms` | SQLite CLI startup and validation duration |
| `sshsqlite.statements.duration_ms` | Statement duration by operation and outcome |
| `sshsqlite.rows.returned_total` | Rows returned by query operations |
| `sshsqlite.sqlite.busy_total` | Busy/locked outcomes by code |
| `sshsqlite.timeouts_total` | Query, ping, connect, read, and write timeouts |
| `sshsqlite.pool.evictions_total` | Pool evictions by reason |

Support diagnostics bundle:

1. Driver version.
2. SQLite CLI version from `sqlite3 --version`.
3. Selected CLI output mode and detected capabilities.
4. `sqlite3.path` and executable permission summary, with usernames/path components redacted if needed.
5. Server OS, architecture, SQLite version, and SQLite compile options where available.
6. Redacted JDBC URL and connection properties.
7. Known-host verification outcome, not private key or password material.
8. Recent redacted driver logs for the connection ID.
9. Recent sanitized `sqlite3` stderr bounded by `stderr.maxBufferedBytes`.
10. Desktop tool name and version when applicable.
11. Exact operation attempted: connect, metadata browse, query, edit, commit, rollback, cancel, or close.

Diagnostics must never include passwords, private key contents, passphrases, SQL parameter values, or unrestricted full SQL text by default.

## Operational Runbook

Initial installation:

1. Create a dedicated remote account where practical.
2. Install or verify a trusted `sqlite3` CLI path whose binary and parent directories are not writable by the SSH login user.
3. Configure SSH account filesystem permissions for the intended database path.
4. Confirm SSH login with host-key verification.
5. Confirm `sqlite3 --version` works over SSH.
6. Connect with the default `readonly=false` for normal read/write use, or set `readonly=true` for inspection-only sessions.
7. Verify table browsing in the target desktop tool.
8. For important live data, confirm the backup/snapshot policy before editing.

Backup requirements before write-capable use:

1. Identify the application owner of the SQLite database.
2. Choose one supported backup method: SQLite online backup API from a trusted local process, `VACUUM INTO` only when enabled by policy and the destination path is authorized, application-aware stop/quiesce followed by file copy, or filesystem snapshot with an atomic consistency boundary for the database and WAL state.
3. Do not copy only the main `.db` file from a live WAL database and call it a backup.
4. Do not sequentially copy `.db`, `-wal`, and `-shm` while writes continue and call it an atomic backup.
5. Store the backup outside the live database directory.
6. Record backup time, database path, journal mode, SQLite version, and backup method.
7. Restore the backup to a disposable location and run `PRAGMA integrity_check`.
8. Open the restored copy through SSHSQLite with `readonly=true` and verify table browsing.

Restore testing:

1. Rehearse restore before the first production write session.
2. Repeat restore testing after driver upgrades, SQLite version changes, or application storage changes.
3. A release is not production-ready for writes unless a backup can be restored and read successfully through the documented workflow.

Automated write-release backup gate:

```bash
./gradlew verifyWriteReleaseBackupRestore
./gradlew verifyRelease -PverifyWriteRelease=true
```

The automated gate creates only disposable databases under the Gradle test temp directory. It creates a WAL-mode fixture, makes a SQLite-consistent backup with `VACUUM INTO`, restores that backup to another disposable path, runs `PRAGMA integrity_check`, then opens the restored copy through SSHSQLite with `readonly=true` and verifies metadata browsing plus a bounded query. It must never point at a live application database.

Successful evidence is written to `build/reports/write-release/backup-restore.json` with schema `sshsqlite-write-release-backup-restore-v1`, `databaseScope=disposable`, the backup method, `integrityCheck=ok`, `sshsqliteReadonlyBrowse=pass`, and a restored fixture SHA-256. Basic CLI release verification does not require this evidence; `./gradlew verifyRelease -PverifyWriteRelease=true` fails closed if the evidence is missing or invalid.

Before write sessions:

1. Confirm the target database and sidecar files are backed up or recoverable.
2. Keep transactions short.
3. Avoid schema changes while the application is running unless the application supports them.
4. Prefer explicit `BEGIN`/`COMMIT` around related edits.
5. Disconnect after admin edits.

When the database is locked:

1. Check whether the game/server process is busy or holding a transaction.
2. Retry after the active workload settles.
3. Increase `busyTimeoutMs` only if waiting longer is acceptable.
4. Avoid long-running reads that prevent WAL checkpoints.

When metadata browsing fails:

1. Enable redacted debug logs.
2. Capture the specific `DatabaseMetaData` method if available.
3. Add conservative method support or stubs rather than broad fake capabilities.
4. Reproduce the workflow in DBeaver first. Other desktop tools should not be documented as supported until tested.

## Test Matrix

Production testing is automation-first. Developers should normally run one command instead of walking every checklist manually.

Required verification commands:

| Command | Purpose |
| --- | --- |
| `./gradlew verify` | Fast local checks: format, unit tests, CLI parser fixtures, JDBC matrix freshness, redaction tests |
| `./gradlew verifyIntegration` | Disposable SQLite DB, remote `sqlite3` CLI, automated SSH test server, JDBC integration tests |
| `./gradlew verifyRelease` | Existing artifact manifests, inventories, signing/provenance evidence, tool evidence, and install guidance; add `-PverifyWriteRelease=true` for disposable backup/restore rehearsal |
| `./gradlew verifySoak` | Long-running reliability, idle, concurrency, large-result, and fault-injection tests |
| `./gradlew verifyTools` | Guided DBeaver smoke evidence capture where GUI automation is not available |

Gradle is the canonical Java ecosystem entry point. Optional helper tasks may delegate to non-Java tooling internally, but the CLI backend is canonical.

Release smoke checklist:

- `docs/release-smoke.md` is the operator-facing checklist for fresh install, `sqlite3 --version`, known-host verification, read-only connect, metadata browse, bounded `SELECT`, read-only rejection, and diagnostics bundle capture.
- `./gradlew generateToolEvidenceTemplate` writes starter DBeaver evidence templates under `build/reports/tool-evidence-template`.
- `./gradlew verifyTools` validates completed evidence under `tool-evidence/`.

`./gradlew verifyTools` validates a structured evidence bundle for desktop GUI workflows when direct automation is unavailable. The bundle lives at `tool-evidence/workflow.json` with schema `sshsqlite-tool-evidence-v1`. It must contain pinned DBeaver entries with tool name/version/build, driver artifact SHA-256, remote SQLite CLI version, disposable fixture SHA-256, redacted log references, screenshot or exported diagnostics references, pass/fail JSON references, unexpected JDBC method trace references, and the read-only workflow IDs listed below. Referenced evidence files must exist under `tool-evidence/` and be non-empty. Generate a starter checklist with `./gradlew generateToolEvidenceTemplate`.

Production `verifyRelease` fails closed without valid tool evidence. Development or preview checks may explicitly bypass missing evidence with `-PallowMissingToolEvidence=true`; this does not validate malformed evidence when a bundle is present.

Manual testing is not an acceptable substitute for automated gates except for pinned desktop GUI workflows that cannot be automated. Manual GUI evidence must include tool version, disposable database fixture, redacted logs, and pass/fail notes for every workflow in this section.

Minimum production test matrix:

| Area | Tests |
| --- | --- |
| URL parsing | user/host/port/path, percent encoding, IPv6, property precedence |
| SSH security | known host success, unknown host fail, changed host fail, revoked key, auth failure |
| Command safety | fixed `sqlite3` command shape, DB path safely escaped as one argument, no SQL on command line, paths with spaces, quotes, and metacharacters |
| SQLite CLI trust | executable availability, trusted path guidance, version/output-mode mismatch |
| Path handling | URL/property path precedence, safe database argument escaping, missing file, permission denied |
| CLI transport | startup validation, persistent repeated requests, unparsable output, oversized output, `sqlite3` EOF |
| Pooling future | Not required for the CLI MVP; max size 5, security-context partitioning, ping validation, broken eviction, transaction reset for the pooling release |
| SQLite | SELECT, INSERT, UPDATE, DELETE, constraints, busy, locked, and readonly rejection |
| Transactions | Autocommit, deferred begin, commit, rollback, close rollback, and failed commit when manual transaction support is implemented; transaction SQL is sent over stdin |
| Results | cursor/fetch batches, early close, max rows, BLOB limits, nulls, `wasNull()` |
| Parameters | CLI parameter emulation limits if used; JDBC index binds, named binds, repeated names, safe SQL literal rendering or temporary parameter commands, and optional limited `.param` preprocessing only after implemented and tested |
| Metadata | tables, columns, indexes, primary keys, foreign keys, schemas |
| JDBC interface | reflection coverage, closed-object behavior, unsupported methods, wrapper/unwrap |
| Tools | DBeaver browse/query smoke tests plus write edit smoke tests on disposable databases |
| Operations | `sqlite3` missing, permission denied, version/output-mode mismatch, query timeout, cancel |

Additional release-gate tests:

| Area | Tests |
| --- | --- |
| Install/upgrade | fresh install, `sqlite3.path` verification, incompatible CLI refusal, rollback to previous driver or SQLite package |
| Backups | live WAL backup method documented, restore to disposable path, `PRAGMA integrity_check`, readonly browse restored copy |
| Observability | required structured events emitted, metrics populated, connection IDs correlate driver logs and CLI diagnostics |
| Redaction | URLs, passwords, key paths, SQL parameters, `.param` values, full SQL, remote paths, CLI stderr |
| Support bundle | diagnostic bundle contains required versions/hashes/capabilities and excludes secrets |
| Soak | repeated connect/query/close, long idle validation, `sqlite3` crash recovery, SSH EOF recovery |
| Large results | bounded memory while fetching batches, early result-set close finalizes remote statement |
| Desktop tools | pinned DBeaver version, readonly browse/query plus write edit/commit/rollback on disposable databases |

Soak pass criteria:

- `1000` sequential connect/query/close cycles complete with no leaked `sqlite3` processes and no stuck SSH sessions.
- `5` concurrent non-pooled physical read-only connections run bounded query loops for at least `1` hour with no leaked `sqlite3` processes, stuck SSH sessions, or cross-connection state leakage.
- Future pooling soak: `5` concurrent pooled read connections run metadata and bounded query loops for at least `1` hour with no pool reuse of broken or tainted connections. This is required for the pooling release, not the CLI MVP.
- One idle connection survives at least `8` hours with CLI validation and SSH keepalive, or fails closed and reconnects cleanly according to documented behavior.
- Bounded large-result CLI smoke reads more rows than the configured JDBC fetch size and verifies final JVM/`sqlite3` resource cleanup. Current sqlite3 CSV CLI mode buffers statement output before JDBC fetch batches, so this is not evidence of true streaming.
- `sqlite3` crash, SSH EOF, unparsable output, stdout write timeout, and query timeout without safe interrupt/finalization mark the connection broken. Query timeout with verified interrupt and cleanup may leave the connection reusable only when a backend implements it. Pooled physical connection eviction is required only when pooling exists or is under pooling-release test.
- Resource growth across the soak must stay within hard ceilings: `0` leaked `sqlite3` processes, `0` unclosed SSH channels after teardown, JVM heap after forced garbage collection no more than `20%` above post-warmup baseline, `sqlite3` RSS no more than `20%` above post-warmup baseline, and open file descriptors no more than `10%` above post-warmup baseline.
- Every failure during soak must produce a redacted structured event and enough diagnostic context to identify the failed connection/request.

`verifySoak` emits machine-readable JSON with command version, git revision when available, OS/arch, Java version, SQLite CLI version, scenario names, pass/fail status, duration, peak memory, final memory, open file descriptors, `sqlite3` process count, SSH channel count, and failure diagnostics.

### DBeaver Smoke Tests

Run these workflows against each supported desktop-tool version before production CLI release:

1. Add the SSHSQLite JDBC driver artifact.
2. Configure a connection with known-host verification, `sqlite3.path`, and the intended `readonly` mode.
3. Connect and verify `sqlite3.path`, SQLite CLI version, and selected output mode appear in diagnostics.
4. Browse schemas, tables, columns, primary keys, indexes, and foreign keys.
5. Open a data grid for a rowid table and a table with an explicit primary key.
6. Run a bounded `SELECT`.
7. Confirm an unbounded large table browse uses fetch batching and does not buffer all rows.
8. Confirm `readonly=true` rejects `INSERT`, `UPDATE`, `DELETE`, DDL, `ATTACH`, and unsafe PRAGMAs.
9. Capture unexpected JDBC metadata calls and update the JDBC support matrix before release.

Read/write tool workflows must run against disposable copies before enabling against live data:

1. Edit one existing row, commit, reconnect, and verify persistence.
2. Start a transaction, edit a row, rollback, reconnect, and verify rollback.
3. Delete one row and verify update count exactly `1` or rollback/fail on any other count.
4. Insert workflows are unsupported until generated-key-disabled behavior is proven in both tools, or until generated-key support is implemented and tested.
5. Trigger a lock/busy condition and verify the tool shows a clear error without breaking a reusable connection when appropriate.

Rowid-only GUI editing is unsupported until pinned DBeaver evidence proves the tool can use a safe exposed `_rowid_` identity. Production GUI editing requires an explicit `INTEGER PRIMARY KEY` or complete non-null primary-key metadata unless that evidence exists.

## Desktop Tool Distribution

The JDBC driver distribution must be desktop-tool ready:

- One self-contained JDBC jar for DBeaver users, with shaded or bundled runtime dependencies except those intentionally provided by the JDK.
- Published Maven coordinates for Java application users.
- Driver class documented as `org.sshsqlite.jdbc.SshSqliteDriver`.
- `META-INF/services/java.sql.Driver` included for service loading.
- Tool-specific setup examples for URL, connection properties, SSH agent/key/password handling, `sqlite3.path`, and redacted diagnostics.
- Smoke evidence must prove required properties can be supplied through each pinned desktop tool without leaking secrets in URLs or logs.
