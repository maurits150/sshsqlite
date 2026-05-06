# Release Smoke Checklist

Use this checklist for release-candidate CLI installs, upgrades, and rollbacks. It is evidence capture for this experimental driver, not a production-safety claim or a replacement for automated release gates. A release candidate still requires signed artifact verification, `./gradlew verifyRelease` without development bypasses, `./gradlew verifySoak -PsoakProfile=full`, and `./gradlew verifyTools` with real DBeaver evidence.

Run write, edit, commit, rollback, admin SQL, schema change, generated-key, or pooling workflows only against disposable databases with explicit release evidence. Do not use this checklist to claim production safety for important live databases.

## Release Candidate Gates

Record these command results before install:

```bash
./gradlew packageRelease
./gradlew verifyRelease
./gradlew verifySoak -PsoakProfile=full
./gradlew verifyTools
```

`./gradlew packageRelease` writes the complete candidate set under `build/distributions/`. `./build.sh` then writes the user-installable `dist/sshsqlite-driver-0.1.0-SNAPSHOT-all.jar` and a matching `dist/sshsqlite_SHA256SUMS`; the GitHub Action runs `./build.sh` and uploads those same `dist/` files.

Development-only preview shortcuts must be labeled as non-production evidence:

```bash
./gradlew verifyRelease -PallowUnsignedRelease=true -PallowMissingToolEvidence=true
./gradlew verifyTools -PallowMissingToolEvidence=true
```

Release evidence must not use `-PallowUnsignedRelease=true` or `-PallowMissingToolEvidence=true`.

## Install Evidence

Capture the following values:

| Field | Value |
| --- | --- |
| Driver version |  |
| Driver artifact path, normally `dist/sshsqlite-driver-0.1.0-SNAPSHOT-all.jar` |  |
| Driver artifact SHA-256 |  |
| Remote sqlite3 path |  |
| Remote sqlite3 version |  |
| Server OS/architecture/libc target |  |
| Protocol version range |  |
| SQLite version |  |
| Readonly mode |  |
| Known-hosts file |  |
| DBeaver version, if used |  |

Required checks:

1. Verify signed checksum and provenance or attestation evidence for the release artifacts.
2. Verify `sha256sum -c sshsqlite_SHA256SUMS` passes from inside `dist/` for the installable artifact, or verify the equivalent manifest beside downloaded release artifacts.
3. Confirm the remote `sqlite3.path`, normally `/usr/bin/sqlite3`, exists and reports a tested SQLite CLI version.
4. Confirm `sqlite3.path` and parent directories are owned by root or a trusted deployment account and are not writable by the SSH login user.
5. Confirm the SSH account has only the database permissions needed for the intended `readonly` mode.
6. Confirm normal CLI configuration does not include `helper.path`, `helper.expectedSha256`, or helper allowlist properties.

## Runtime Smoke

Run these checks against a disposable or approved read-only preview database:

1. `ssh alice@db.example.org '/usr/bin/sqlite3 --version'` reports the expected SQLite CLI version.
2. SSH host-key verification succeeds with the pinned `ssh.knownHosts` file.
3. A default connection with `readonly=false` and `sqlite3.path=/usr/bin/sqlite3` opens successfully without helper properties, using a generic URL such as `jdbc:sshsqlite://alice@db.example.org:22/srv/app/app.db`.
4. Diagnostics show driver version, backend type, SQLite version, `sqlite3.path`, output mode, and known-host verification outcome.
5. Metadata browsing lists schemas, tables, columns, primary keys, indexes, and foreign keys without unexpected JDBC method failures.
6. A bounded `SELECT` succeeds, for example `SELECT * FROM example_table LIMIT 10` or a fixture-specific equivalent.
7. Large table browsing or a fixture large-result query respects the driver's bounded result limits; do not claim true streaming or unbounded fetch batching unless current code and evidence prove it.
8. When `readonly=true` is set, read-only rejection is clear for `INSERT`, `UPDATE`, `DELETE`, DDL, `ATTACH`, and unsafe PRAGMAs. When `readonly=false`, normal read/write SQL is expected to work only on disposable or explicitly approved databases.
9. Closing the result set and connection terminates the remote `sqlite3` process without leaked processes or stuck SSH sessions.
10. A support diagnostics bundle contains the required fields from `docs/operations.md` and excludes passwords, private key contents, passphrases, SQL parameter values, and unrestricted full SQL text.

## Desktop Tool Evidence

When using DBeaver, capture evidence through the existing Gradle template and validator:

```bash
./gradlew generateToolEvidenceTemplate
./gradlew verifyTools
```

The completed `tool-evidence/workflow.json` bundle must include a pinned DBeaver entry, driver artifact hash, remote SQLite CLI version, disposable fixture hash, redacted logs, screenshots or exported diagnostics, pass/fail JSON, and unexpected JDBC method traces for these workflow IDs:

- `add-driver-artifact`
- `configure-readonly-known-host-sqlite3-path`
- `connect-and-diagnostics`
- `browse-metadata`
- `open-data-grids`
- `run-bounded-select`
- `verify-fetch-batching`
- `verify-readonly-rejections`
- `capture-unexpected-jdbc-methods`

## Pass Criteria

The smoke passes only if all of these are true:

- Artifacts and expected hashes are verified from signed release evidence.
- The configured `sqlite3.path` exists at a trusted, non-user-writable path.
- Host-key verification fails closed for unknown or changed hosts.
- The connection opens with default `readonly=false` and no helper properties for normal CLI mode.
- Metadata browse and bounded `SELECT` work in the target application or desktop tool.
- Mutating SQL and unsafe read-only bypass attempts are rejected when `readonly=true`; read/write behavior with `readonly=false` is exercised only against disposable or explicitly approved databases.
- Diagnostics are useful and redacted.
- No development bypasses are used for release evidence.

If any check fails during upgrade, roll back by restoring the previous driver artifact or `sqlite3.path`/server SQLite package, evicting all physical connections for that security context, and rerunning this checklist against the previous configuration.
