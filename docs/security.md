# Security

## Threat Model

Assets:

- SQLite database contents and integrity.
- SSH credentials, private keys, passwords, passphrases, and agent access.
- Remote filesystem paths reachable by the SSH account.
- Remote `sqlite3` binary trust.
- SQL query text and parameter values.
- Operational logs and protocol traces.

Trusted parties:

- The local JDBC driver process.
- The desktop database tool using the driver, within the user's workstation trust boundary.
- The authenticated SSH account, constrained by least privilege.
- The pinned SSH host key.
- The remote `sqlite3` binary selected by the server operator.

Not trusted:

- The network between client and server.
- Unknown, changed, revoked, or mismatched SSH host keys.
- User-controlled JDBC URLs.
- Database contents returned from SQLite.
- Remote writable directories that are not owned securely by the SSH user.
- Unexpected remote `sqlite3` binaries or writable executable paths.

Security goals:

- Prevent network man-in-the-middle attacks through mandatory SSH host-key verification.
- Prevent command injection when starting `sqlite3`.
- Use least-privilege SSH accounts and safe path handling for database file access.
- Treat optional helper tampering controls as helper-mode only.
- Avoid leaking secrets through URLs, logs, exceptions, helper stderr, or protocol traces.
- Preserve SQLite locking semantics and avoid corrupting live databases.

Non-goals:

- Prevent an authorized write-capable user from executing destructive SQL against an authorized database.
- Protect against a fully compromised remote host.
- Provide row-level or table-level authorization unless an explicit SQL policy feature is added.
- Make SQLite behave like a multi-writer client/server database.

## SSH Host Key Verification

SSH host-key verification is mandatory.

Requirements:

- The driver must verify the server host key against an OpenSSH-compatible `known_hosts` file before authentication or remote `sqlite3` execution.
- `ssh.knownHosts` defaults to the user's standard known-hosts file.
- Unknown, changed, revoked, or mismatched host keys must fail closed.
- Production mode must not trust on first use and must not auto-write new keys to `known_hosts`.
- The driver must not provide a trust-all host-key verifier in production configuration.
- Any insecure development override must be explicitly named, disabled by default, and logged as a warning.
- OpenSSH hashed host entries, bracketed `[host]:port` entries, `@revoked` markers, and non-default ports must be supported or explicitly rejected with a clear error.
- Host certificates and `@cert-authority` entries must be either supported correctly or explicitly unsupported; they must not fall back to trust-all behavior.
- Host aliases and canonical DNS behavior must be deterministic and documented.
- Prefer modern host-key algorithms such as `ssh-ed25519` and `rsa-sha2-*`.
- Do not accept deprecated `ssh-rsa` SHA-1 signatures unless explicitly configured for a legacy host.

Known-host lookup identity:

- The lookup host is the final `ssh.host` property after URL/property precedence is applied.
- Non-default ports use OpenSSH bracket form `[host]:port` for known-host matching.
- IPv6 literals use OpenSSH bracket form with the configured port.
- No DNS canonicalization, alias rewriting, or automatic host-key learning is performed unless an explicit future feature documents it.
- Hashed `known_hosts` entries and `@revoked` markers must be honored or fail closed if unsupported by the SSH library.

## Production Mode

Production-safe behavior is the default.

- There is no implicit development mode.
- Any development override must be explicitly named, for example `developmentMode=true`.
- Development mode may relax signing or host-key learning only where the docs explicitly say so.
- Production mode fails closed when required production inputs are absent: known host, safe `sqlite3.path` configuration, and signed release provenance for driver artifacts.

## SSH Authentication

Production should prefer key-based authentication.

Supported authentication order is explicit and deterministic:

1. Configured private key, if provided.
2. Password, if provided and allowed.

Requirements:

- Support encrypted private keys through passphrases supplied out of band.
- Do not put passwords, private key material, or passphrases in the JDBC URL.
- Accept secrets through connection properties, desktop-tool credential stores, or environment-specific secret providers.
- SSH agent authentication is disabled in the self-contained desktop-tool jar because Apache SSHD's Unix agent support depends on optional native APR/Tomcat classes.
- Do not log secrets or full connection properties.
- Hold secrets in memory only as long as required where the Java/SSH library permits.
- Authentication failure messages must not reveal more than normal SSH semantics.

## Remote Command Execution

The remote `sqlite3` CLI must be started without shell injection risk.

Requirements:

- `sqlite3.path` must use the documented deterministic default `/usr/bin/sqlite3` or an explicit operator-supplied path.
- Relative paths and PATH lookup are not used by default. The only relative executable name allowed by command validation is exactly `sqlite3`, and production deployments should prefer an absolute path such as `/usr/bin/sqlite3`.
- Command substitution, shell metacharacters, pipes, redirection, and compound commands must be rejected.
- The production remote command may execute only `sqlite3` with fixed safe options and the remote database path as the SQLite database argument.
- SQL, parameter values, and user-controlled SQLite options must be sent on stdin, not on the remote shell command line.
- DB paths, executable paths, usernames, options, and SQL must never be shell-interpreted.
- If the SSH library only accepts a single command string, the implementation must use a documented POSIX single-quote escaping function for the executable path, fixed trusted arguments, and database path, with tests for spaces, quotes, metacharacters, and non-ASCII paths.
- A fixed server-side wrapper is acceptable only if it accepts no untrusted shell syntax and receives SQL on stdin.

Allowed production command shape after validation and escaping:

```text
/usr/bin/sqlite3 -batch /absolute/path/database.db
```

Optional trusted config paths may be fixed by deployment policy, but they must not come from untrusted JDBC URL values.

Rejected `sqlite3.path` examples:

```text
~/bin/sqlite3
/tmp/sqlite3; rm -rf /
/home/user/sqlite3 $(whoami)
/home/user/sqlite3 | sh
```

## Database Path Authorization

Canonical CLI mode relies on SSH account permissions and server operator controls for database file access. Driver-side path validation prevents accidental unsafe command construction, but it is not a server-side authorization boundary.

Requirements:

- Use a least-privilege SSH account with filesystem permissions only for intended database files.
- Canonicalize the requested `db.path` with symlink resolution before authorization.
- Allowlisted directories must not be writable by untrusted users.
- Reject symlink database files unless symlinks are explicitly allowed and the resolved target is authorized.
- Reject paths outside the allowlist after canonicalization.
- Reject relative paths unless explicitly resolved against a configured base directory.
- Reject paths containing NUL bytes or invalid encoding.
- Reject non-regular files unless explicitly allowed.
- Open the database only after safe path parsing and command construction.
- Use race-resistant open patterns where practical. If the platform cannot make canonicalize-then-open race-free, require allowlisted parent directories to be trusted and non-writable by attackers.
- Apply the same authorization to SQLite sidecar and write-target files, including `-wal`, `-shm`, rollback journals, temporary files, and `VACUUM INTO` destinations.
- `ATTACH` and `VACUUM INTO` are denied by default. If either is enabled, the helper must authorize the resolved destination path before execution.
- WAL, shared-memory, journal, and temp-file creation require trusted database parent directories that are not writable by attackers.
- Do not rely on client-side URL validation as a security boundary.

Optional helper mode may provide a server-side allowlist. Normal CLI mode has no helper allowlist requirement.

### Optional Helper Allowlist Configuration

This section applies only to optional helper mode. Normal CLI mode does not require or load a helper allowlist.

The helper loads allowlist configuration from fixed `/etc/sshsqlite/allowlist.json` unless a root/deployment-owned fixed config path is passed as a trusted helper startup argument. Missing, unreadable, malformed, group-writable, other-writable, or unexpectedly owned allowlist files fail closed in helper production mode.

Minimal schema:

```json
{
  "version": 1,
  "databases": [
    {
      "path": "/srv/app/app.db",
      "mode": "readwrite",
      "allowAdminSql": false,
      "allowSchemaChanges": false
    }
  ],
  "directoryPrefixes": [
    {
      "path": "/srv/gmod/garrysmod/readonly-dbs",
      "mode": "readonly"
    }
  ],
  "tempDirectories": [
    "/var/tmp/sshsqlite"
  ],
  "allowSymlinks": false
}
```

Rules:

- `version` is required and must be supported by the helper.
- Exact database paths are preferred over directory prefixes.
- Directory prefixes must resolve to trusted, non-attacker-writable directories.
- `mode` is `readonly` or `readwrite`; readwrite still requires `writeBackupAcknowledged=true` in production.
- `allowAdminSql` and `allowSchemaChanges` default to `false`.
- `tempDirectories` are optional and must be path-authorized, bounded, and non-attacker-writable.
- Runtime reload is disabled for MVP. Changing allowlist policy requires starting a new helper/connection.
- The helper reports allowlist policy version and a redacted allowlist hash in `openAck` for diagnostics and pool partitioning.
- The helper must log a redacted allowlist decision for diagnostics without exposing secrets.
- Optional helper mode may reject `readonly=false` unless the resolved database entry is allowlisted as `readwrite` and any configured production backup acknowledgement is present. This policy is helper-specific and is not required for the canonical CLI backend.
- `adminSql=true` and `allowSchemaChanges=true` require both client flags and matching server allowlist policy.

## Remote SQLite Binary Trust

Normal CLI mode does not require `helper.expectedSha256` or helper allowlist configuration. The remote `sqlite3` binary is trusted as part of the server environment.

Requirements:

- Operators should use an OS-packaged or otherwise trusted `sqlite3` binary.
- The SSH login user should not be able to replace `sqlite3.path` or its parent directories.
- Do not execute `sqlite3` from world-writable directories such as `/tmp`.
- If an optional helper backend is used, helper path/hash verification rules apply to that backend only.

If legacy `helper.autoUpload` is implemented, integrity verification is mandatory for helper mode:

- Verify the local helper artifact hash before upload.
- Upload to a securely created per-user directory with mode `0700`.
- Write the helper with mode `0700` or `0755`, never world-writable.
- Verify the remote uploaded helper hash before execution.
- Pin the expected hash by driver release or trusted configuration.
- Avoid replacing an existing helper unless existing file hash, owner, and permissions match expectations.
- Refuse to execute an uploaded helper if ownership, permissions, or hash verification fails.

## SQL Execution Boundary

SQL is data for SQLite only.

Requirements:

- SQL must never be interpolated into shell commands, `sqlite3` command lines, filenames, logs, or secondary interpreters.
- `PreparedStatement` must bind values through SQLite parameters when true binding is implemented. CLI-mode emulation, if present, must be documented as limited and must never use shell interpolation.
- SQLite shell dot commands are not SQL. They must be rejected unless an exact compatibility preprocessor implements that command safely.
- Metadata SQL must safely quote identifiers and use SQLite 3.27-compatible `sqlite_master` plus schema-qualified PRAGMA statements.
- User-controlled table, index, schema, or column names must never be concatenated without SQLite identifier quoting.

Trust boundary:

- Read/write mode is normal SQLite access through the remote `sqlite3` CLI. It intentionally allows users and desktop tools to run their own SQL scripts, including DDL, PRAGMAs, transactions, and maintenance commands that SQLite accepts.
- The driver is not a SQL firewall. It prevents shell injection, keeps SQL off the SSH command line, and must clean up sqlite3 promptly when JDBC loses the connection.
- Production deployments should have a SQLite-consistent backup and restore procedure before using `readonly=false` against important live databases.
- Write-release verification should include passing disposable backup/restore rehearsal evidence from `./gradlew verifyWriteReleaseBackupRestore` or `./gradlew verifyRelease -PverifyWriteRelease=true`.
- The driver does not protect applications from SQL injection in application-composed `Statement` SQL.
- Users should prefer `PreparedStatement` for values.

## Readonly Enforcement

`readonly=true` must be enforced in multiple layers.

Requirements:

- Open SQLite with read-only flags equivalent to `SQLITE_OPEN_READONLY`.
- Set `PRAGMA query_only=ON` after opening where supported.
- Install a SQLite authorizer only when a helper/direct SQLite backend exists; CLI mode should use read-only open options, `PRAGMA query_only=ON`, and pre-execution rejection where detectable.
- Reject write operations before execution where detectable.
- Reject write operations before execution where detectable.
- User SQL that SQLite classifies as read-only but has connection-local effects may still be handled by SQLite; `readonly=true` is best-effort through `-readonly`, `PRAGMA query_only=ON`, and JDBC write-path rejection.
- Set `PRAGMA temp_store=MEMORY` for readonly sessions where practical, or configure a path-authorized temp directory with bounded memory/disk limits.
- Readonly sessions must not write temp files outside SQLite internals needed for read-only query execution and authorized temp policy.
- Treat readonly violations as statement errors, not connection-fatal errors.

`immutable=1` must not be used for live databases. It is only appropriate for static snapshots because it can ignore external changes and locking.

## SQLite Extension Loading And ATTACH

The backend must keep extension loading unavailable. In CLI mode, the driver must not send `.load` and must reject user-supplied dot commands that could load extensions.

Helper/direct SQLite backends may use `sqlite3_set_authorizer` or equivalent controls for stronger policy enforcement. Canonical CLI mode does not implement a SQL firewall and intentionally allows normal sqlite3 scripting in read/write mode.

Policy backends can deny:

- `load_extension`.
- `ATTACH` and `DETACH` unless explicitly enabled.
- `VACUUM INTO` unless destination path is authorized.
- `PRAGMA writable_schema`, `journal_mode`, `synchronous`, `locking_mode`, `page_size`, `auto_vacuum`, unsafe `wal_checkpoint(TRUNCATE)` during live use, and any PRAGMA not on an explicit allowlist for the active mode.
- `foreign_keys` changes inside write workflows unless explicitly configured.
- Writes while `readonly=true`.

Authorizer implementation applies only to helper/direct SQLite backends. The CLI backend must honestly document weaker enforcement and test the controls it does implement: read-only open behavior where supported, `PRAGMA query_only=ON`, dot-command rejection, write-statement rejection where detectable, read/write execution when `readonly=false`, and prompt sqlite3 process cleanup when the connection breaks.

### SQL Authorizer Matrix

This matrix applies only to optional helper/direct SQLite policy backends. It is not the canonical CLI mode behavior.

| Operation class | Readonly | Data-edit write mode | Admin SQL mode |
| --- | --- | --- | --- |
| `SELECT` from authorized primary DB | Allow | Allow | Allow |
| `INSERT`, `UPDATE`, `DELETE` on authorized primary DB | Deny | Allow | Allow |
| Writes to `sqlite_%` internals | Deny | Deny | Explicit allowlist only |
| DDL: `CREATE`, `ALTER`, `DROP` | Deny | Deny | Requires `allowSchemaChanges=true` |
| `ATTACH`, `DETACH` | Deny | Deny | Path-authorized explicit allowlist only |
| `VACUUM INTO` | Deny | Deny | Path-authorized explicit allowlist only |
| `load_extension` | Deny | Deny | Deny unless separately reviewed |
| Virtual table modules with side effects | Deny | Deny | Explicit allowlist only |
| Durability-changing PRAGMAs | Deny | Deny | Explicit allowlist only |
| Read-only PRAGMAs | Allow if allowlisted | Allow if allowlisted | Allow if allowlisted |
| Temp writes | Only authorized temp policy | Only authorized temp policy | Only authorized temp policy |

For tool-generated edits, the driver/helper must quote identifiers, bind values, include original key values in predicates, require update count exactly `1`, and fail or rollback when update count is `0` or greater than `1`.

If `ATTACH` is supported, attached database paths must go through the same canonicalization and allowlist authorization as the primary `db.path`.

## Pooling Security

Pooled connections must not cross security boundaries.

Requirements:

- Pools must be partitioned by SSH host, port, user, authentication identity, host-key policy, `sqlite3.path`, database path, readonly mode, output mode, and negotiated/detected capabilities.
- Read-write physical connections must never be reused for readonly borrowers.
- Physical connections with different `db.path` values must never share a pool entry.
- Returning a connection to the pool must clear connection-scoped parameter maps, warnings, client info, and mutable JDBC state.
- If rollback, reset, or validation fails, close and evict the physical connection.

## Least Privilege

Production deployments use a dedicated Unix account for SSHSQLite.

Requirements:

- Do not run remote database access as root.
- Do not reuse broad personal or administrator SSH accounts for production.
- Grant the SSH account access only to intended database files and required `sqlite3` execution.
- Grant database file permissions only for the required read/write mode.
- Disable interactive shell access where practical through forced commands or restricted `sshd` configuration.
- Use OS sandboxing where available.
- Keep executable directories owned by root or a deployment account, never writable by the SSH login user or dedicated runtime account.

## Secrets And Logging

Logging must be safe by default.

Requirements:

- Do not log `ssh.password`, private key contents, passphrases, tokens, or full connection properties.
- Do not log SQL parameter values by default.
- Do not log full SQL by default in production.
- Provide opt-in debug logging with redaction.
- Redact credentials from JDBC URLs and exception messages.
- Avoid returning remote absolute paths in user-facing errors unless debug mode is enabled.
- Capture helper stderr separately and sanitize it before surfacing through JDBC logs.
- Protocol trace mode must be opt-in and must redact secrets and parameter values by default.

## Security Defaults

Recommended production defaults:

| Setting | Default |
| --- | --- |
| Host-key verification | Required |
| Unknown host key | Fail closed |
| Password in URL | Rejected |
| `sqlite3.path` | Deterministic configured/default path |
| Path allowlist | Not required for normal CLI mode |
| Readonly | `false`; set `readonly=true` explicitly for inspection-only sessions |
| Extension loading | Disabled |
| Helper auto-upload | Legacy/helper mode only |
| SQL/parameter logging | Redacted/off |
| Executable from `/tmp` | Rejected |
