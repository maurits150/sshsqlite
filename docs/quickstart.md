# SSHSQLite CLI Quickstart

This quickstart uses the normal SSH backend: the Java driver starts the remote `sqlite3` CLI over SSH and sends SQL on stdin. Read/write connections are the default; set `readonly=true` only when you want an explicitly read-only session. No custom helper binary is required for normal use.

## Build Or Download Artifacts

For a local preview build:

```bash
./gradlew packageRelease
```

Release candidates are written under `build/distributions/`. Production operators should download the published driver jar, `sshsqlite_SHA256SUMS`, signed checksum evidence, signed provenance or attestation, dependency inventories, and release metadata from the trusted release location.

Use `./gradlew verifyRelease` only after release artifacts already exist. Production verification fails closed without signed checksum and provenance evidence. Local unsigned preview candidates may be checked explicitly with `./gradlew verifyRelease -PallowUnsignedRelease=true -PallowMissingToolEvidence=true`, but those bypasses are development-only and do not support a production read-only claim.

Optional production signing hooks can be supplied to `verifyRelease` with `-PcosignVerifyManifestCommand=...`, `-PgpgVerifyManifestCommand=...`, `-PcosignVerifyProvenanceCommand=...`, or `-PgpgVerifyProvenanceCommand=...`.

## Verify Artifacts

Verify the signed checksum manifest and provenance before installing anything. Example release commands, with placeholders replaced by the published release identity:

```bash
cosign verify-blob --bundle sshsqlite_SHA256SUMS.bundle --certificate-identity <release-identity> --certificate-oidc-issuer <issuer> sshsqlite_SHA256SUMS
sha256sum -c sshsqlite_SHA256SUMS
cosign verify-attestation --type slsaprovenance --certificate-identity <release-identity> --certificate-oidc-issuer <issuer> <artifact-ref>
```

## Verify Remote SQLite

Install or verify a trusted `sqlite3` CLI on the SSH server:

```bash
ssh sshsqlite@example.com '/usr/bin/sqlite3 --version'
```

The SSH login user must not be able to replace `/usr/bin/sqlite3` or any parent directory. If you configure another `sqlite3.path`, verify that path and its parent directories are trusted.

## Configure Database Permissions

Grant the SSH account only the database filesystem permissions needed for the intended mode:

```text
/srv/app/data.db readable by sshsqlite for readonly=true
/srv/app/data.db and sidecars readable/writable by sshsqlite for readonly=false
/usr/bin/sqlite3 managed by the server package manager or operator
```

Do not put SQL or user-controlled database paths into ad hoc SSH shell commands. The driver safely passes the database path as the `sqlite3` argument and sends SQL only on stdin.

## Configure Known Hosts

Pin the SSH host key locally:

```bash
ssh-keyscan -p 22 example.com >> ~/.ssh/known_hosts
```

Review the scanned key fingerprint out of band before trusting it. Production connections must fail closed on unknown or changed host keys.

## Configure JDBC

Minimal JDBC URL and properties:

```text
jdbc:sshsqlite://sshsqlite@example.com:22/srv/app/data.db

ssh.knownHosts=/home/me/.ssh/known_hosts
ssh.privateKey=/home/me/.ssh/id_ed25519
# or provide ssh.password through your application's Properties/credential store
ssh.agent=false
sqlite3.path=/usr/bin/sqlite3
readonly=false
```

Use connection `Properties` or the desktop tool property grid for secrets. Do not put passwords, private key material, or SQL in SSH commands. The driver starts `sqlite3` with `-batch`, adds `-readonly` only when `readonly=true`, passes the database path as the SQLite database argument, and sends SQL on stdin.

## Desktop Tool Setup Summary

DataGrip and DBeaver setup is intentionally property-driven:

1. Add the self-contained `sshsqlite-driver-<version>-all.jar` from the release artifacts.
2. Use the `jdbc:sshsqlite://sshsqlite@example.com:22/srv/app/data.db` URL.
3. Put `ssh.knownHosts`, `ssh.privateKey` or credential-store password, `sqlite3.path=/usr/bin/sqlite3`, and the intended `readonly` value in driver properties, not in the URL.
4. Verify diagnostics show the expected driver version, SQLite CLI version, selected output mode, and `sqlite3.path`.
5. Run read-only metadata browse and bounded `SELECT` workflows first; use `readonly=false` only after confirming database permissions and backup/restore expectations.

## Local Verification

```bash
./gradlew verify
./gradlew verifyIntegration
./gradlew packageRelease
./gradlew verifyRelease -PallowUnsignedRelease=true -PallowMissingToolEvidence=true
./gradlew generateToolEvidenceTemplate
./gradlew verifyTools -PallowMissingToolEvidence=true
```

For production release readiness, run `./gradlew verifyRelease` without unsigned or missing-evidence bypasses, run `./gradlew verifySoak -PsoakProfile=full`, and validate real DataGrip/DBeaver evidence with `./gradlew verifyTools`.
