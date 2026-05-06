# Agent Notes

This file captures project-specific guidance for future agents. Keep changes aligned with these rules unless the user explicitly changes direction.

## Product Intent

- The normal backend is the remote `sqlite3` CLI over SSH.
- Do not require a custom server-side helper for normal use.
- Do not require `helper.path`, helper hashes, helper allowlists, or helper installation for normal desktop-tool setup.
- The remote server must already have `sqlite3` installed and available at the configured path, defaulting to `/usr/bin/sqlite3`.
- The driver should support normal database-tool usage, including read/write SQL, scripts, DDL, PRAGMAs, and transactions when `readonly=false`.
- Do not cripple functionality by adding a SQL firewall unless the user explicitly asks for one.
- `readonly=false` is the normal default. `readonly=true` is opt-in and should pass `-readonly` plus connection-level read-only protections where possible.
- If an implementation direction conflicts with the README or product intent, stop and fix the design instead of continuing to build the wrong architecture.
- Treat user-reported desktop-tool errors as integration bugs to diagnose from the exact error text and actual remote `sqlite3` output, not as reasons to narrow functionality.
- Prefer the simplest normal-user setup: add the jar to the desktop tool, provide an SSH JDBC URL, ensure remote sqlite3 exists, and connect.
- Avoid adding mandatory setup knobs unless they are truly required for normal CLI-over-SSH operation.

## SQLite Compatibility

- Support old SQLite CLI versions, including SQLite 3.27.x.
- Do not rely on `.mode json`; use portable CLI output modes such as CSV with headers and an explicit null sentinel unless a newer-version requirement is explicitly accepted.
- Use `sqlite_master` for table/view discovery; do not require `sqlite_schema`.
- Prefer schema-qualified PRAGMA statements compatible with SQLite 3.27, such as `PRAGMA main.table_xinfo('table')`, instead of modern-only assumptions.
- Preserve and pass through user SQL as much as possible. Do not strip `main.` or rewrite schema-qualified SQL unless the exact root cause is proven and tests cover it.
- SQLite understands `main` as the primary attached database/schema. Treat `main.table_name` as valid SQL.
- When sqlite3 output parsing fails, include a bounded, redacted snippet of actual stdout/stderr in diagnostics so the next failure is actionable.
- Prefer proving behavior against the installed local/remote sqlite3 CLI over assuming based on newer SQLite docs.
- Avoid generated columns, STRICT tables, RETURNING, `.parameter`, and newer CLI features in baseline tests unless the feature is explicitly version-gated.
- Do not assume local sqlite3 and remote sqlite3 have the same version or compile options.
- Do not depend on JSON, table-valued PRAGMAs, modern schema aliases, or generated-column behavior in baseline metadata browsing.
- If a compatibility workaround seems necessary, document the SQLite version that requires it and add a regression test.

## JDBC And Desktop Tools

- DBeaver is the primary documented tool for now. Keep README setup focused on DBeaver unless more tools are tested.
- The driver class is `org.sshsqlite.jdbc.SshSqliteDriver`.
- The distributed jar should be easy to grab from `dist/` without forcing users to build.
- `DatabaseMetaData.getColumns().TYPE_NAME` must preserve SQLite declared type strings from `PRAGMA table_xinfo.type`, such as `VARCHAR(255)`, `DATETIME`, `BOOLEAN`, `DECIMAL(10,2)`, and `JSON`.
- `DatabaseMetaData.getTypeInfo()` should include common declared type names desktop tools expect, not only SQLite storage classes.
- Accept common desktop/JDBC credential aliases such as `user`, `username`, `password`, `privateKey`, `identityFile`, and known-hosts aliases, while explicit `ssh.*` properties win.
- If no auth properties are given, discover common `~/.ssh` private keys before failing.
- Do not assume desktop tools pass SSH credentials using project-specific names. Support normal JDBC username/password fields and common aliases.
- Keep `ResultSet` and `DatabaseMetaData` capability claims conservative and consistent with what the driver actually implements.
- DBeaver may emit schema-qualified SQL, quoted identifiers, DDL, explicit transaction statements, and maintenance PRAGMAs. Preserve these unless there is a proven SQLite incompatibility.
- Type information matters for UI dropdowns: `getTypeInfo()` affects create/edit dialogs, while `getColumns()` affects existing column display.
- Desktop tools may call metadata APIs in surprising orders and may retry failed SQL. Keep metadata methods side-effect free.
- Do not claim JDBC features such as true streaming, scrollability, updatable result sets, batch updates, or cancellation unless implemented and tested.
- If the UI displays confusing behavior, identify which JDBC metadata method feeds that UI before changing unrelated code.

## SQL Handling

- Preserve user SQL and scripts. Do not strip schemas, rewrite statements, or block SQL based on text scanning unless explicitly requested and covered by tests.
- SQL should go over sqlite3 stdin, never on the SSH command line.
- PreparedStatement values may be rendered as SQL literals for CLI mode only with carefully tested escaping for text, NULL, numbers, booleans, and BLOBs.
- Do not concatenate untrusted identifiers into metadata SQL without SQLite identifier quoting.
- If a statement fails, stop cascading execution where possible and surface the first useful sqlite3 error instead of later rollback/commit noise.
- Dot commands should be controlled by the driver, not accepted from arbitrary user SQL unless explicitly designed and tested.
- Multi-statement scripts are valid DB-tool usage. If parsing or execution cannot handle a case, fail clearly rather than silently rewriting user SQL.
- DDL, PRAGMAs, VACUUM, ATTACH, and transaction scripts are normal SQLite tool operations in read/write mode; do not block them for pseudo-safety.

## SSH Runtime Packaging

- The fat jar must be self-contained for desktop tools.
- Do not rely on optional native/APR/Tomcat classes, Netty, MINA native providers, or SSH agent native libraries unless they are intentionally bundled and tested.
- Force Apache SSHD to pure-Java NIO2.
- Keep required Apache SSHD common classes; do not over-exclude packages just to remove optional native implementations.
- Include required crypto and logging dependencies in the fat jar.
- Exclude dependency signature files from shaded jars: `META-INF/*.SF`, `*.RSA`, `*.DSA`, `*.EC`.
- Audit the jar after dependency changes for missing classes and accidental optional native providers.
- After every packaging fix, test the jar contents directly with `jar tf`/`jdeps` for missing classes, signature files, native providers, and service-loader surprises.
- A class missing in DBeaver is a packaging bug until proven otherwise; do not ask users to add random transitive jars if the fat jar is supposed to be self-contained.
- If excluding packages from shaded dependencies, verify no required common classes were removed.
- Keep Java bytecode compatible with Java 11 unless the README and build target are deliberately changed.
- Desktop tools may run the driver in an isolated classloader. Do not assume classes from the IDE or local JDK extensions are visible.
- Shaded dependency changes should be followed by a clean rebuild, not only an incremental package task.

## Diagnostics And Redaction

- Error messages must be actionable: include bounded, redacted sqlite3 stdout/stderr snippets for parse and startup failures.
- Redact passwords, passphrases, key paths, private local paths, full SQL when appropriate, and remote database paths in generic diagnostics.
- Do not over-redact sqlite3 errors so much that the root cause disappears. Keep table/column names visible unless they are clearly private paths or secrets.
- Prefer surfacing the first sqlite3 error over wrapping everything as a generic connection failure.

## Lock Safety

- Lock safety means preserving SQLite's native local locking by running `sqlite3` on the remote host and ensuring the driver does not leave a process holding locks after JDBC loses the connection.
- Do not use SFTP/file-copy access for live DB reads or writes.
- On timeout, EOF, parse failure, broken protocol, or broken connection, abort the underlying `sqlite3` process or SSH exec channel promptly.
- On normal close, close stdin/EOF first, wait briefly, then force-close if sqlite3 does not exit.
- Do not report failed writes/commits as success. Statement errors from sqlite3 must be detected synchronously enough to avoid false success.
- Surface busy/locked errors clearly as lock contention where possible.
- If CLI mode buffers results, enforce bounded result limits and document them. Do not pretend fetch size is true network streaming if everything is already buffered.
- Lock safety is not the same as preventing destructive SQL. The driver should allow normal DB-tool operations in read/write mode while ensuring processes and locks are cleaned up correctly.
- Tests should cover the canonical CLI backend, not only legacy/helper fixtures.
- WAL, busy/locked contention, timeout, process death, pool reuse, and transaction rollback-on-close need direct tests.
- Unknown outcome after a mutating timeout or broken connection must mark the connection broken and non-reusable.
- Closing a JDBC `Connection`, `Statement`, or `ResultSet` should not depend on the desktop tool behaving perfectly; cleanup must be defensive and idempotent.
- Pooling must never reuse a connection after timeout, parse failure, EOF, or unknown mutating outcome.
- Busy timeout should map to sqlite3 `.timeout` before user statements run.

## Testing And Verification

- Reproduce bugs with the smallest local sqlite3/driver test before patching broad behavior.
- Add regression tests for user-visible failures, especially DBeaver-generated SQL and old SQLite CLI behavior.
- Do not leave tests asserting private paths, hostnames, or usernames.
- Run `./gradlew verify packageRelease` after code changes that affect the jar.
- Run `./build.sh` when `dist/` needs updating.
- If a test fails after changing architecture, fix the test only after confirming the code behavior is correct.
- Keep tests generic: no real hostnames, usernames, absolute home paths, or real database names.
- Include tests for desktop-tool-shaped URLs and properties, not only hand-written ideal properties.
- For packaging fixes, verify the exact jar in `dist/` or `build/distributions/`, not just compiled classes.
- If GitHub Actions are changed, keep the local `build.sh` workflow equivalent so local and CI artifacts match.

## Documentation And Distribution

- Keep README user-first: grab `dist/` jar, configure DBeaver, then build-yourself instructions near the bottom.
- Clearly state that `sqlite3` must exist on the remote server.
- Keep warnings honest and prominent: this project is experimental and not production safe.
- Keep docs and code in sync. Update docs before or alongside code when architecture/defaults change.
- Avoid personal examples in docs/tests. Use generic placeholders like `alice@db.example.org` and `/srv/app/app.db`.
- README should not start with build steps. Put the prebuilt `dist/` jar and DBeaver setup first; build-yourself instructions go later.
- If a warning says the project is unsafe/experimental, keep that warning visible and do not soften it into production claims.
- Update `dist/` after rebuilding the jar so users grabbing the repo get the current driver.
- GitHub Actions and `build.sh` should produce the same jar users are told to install.
- Do not document untested tools as supported. DBeaver is documented; other tools should be described only after evidence exists.
- Keep server requirements explicit: remote SSH access, remote sqlite3 CLI, DB file permissions, and local known_hosts/private-key/password expectations.
- If `dist/` changes, commit the updated jar and checksum together.

## Git And Release Hygiene

- The repository has `build.sh` to build and copy the jar into `dist/`.
- The local pre-commit hook should run `build.sh`, but do not assume every clone has hooks installed.
- GitHub Actions should build the jar and upload `dist/` artifacts.
- Before pushing, ensure the working tree does not contain private hostnames, usernames, absolute local paths, or real database names.
- Keep generated binaries in sync with source. If a jar was built from stale code, rebuild it and commit the updated jar/checksum together.
