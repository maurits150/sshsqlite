# SSHSQLite Quickstart

> WARNING: SSHSQLite is experimental prototype software and is not production safe. Do not point it at important databases unless you have backups, understand the failure modes, and are prepared to debug or repair issues yourself.

This quickstart uses the normal SSH backend: the Java driver starts the remote `sqlite3` CLI over SSH and sends SQL on stdin. Read/write connections are the default; set `readonly=true` only when you want an explicitly read-only session. No custom helper binary is required for normal use.

## Grab The Jar

Use the prebuilt self-contained driver jar from this repo:

```text
dist/sshsqlite-driver-0.1.0-SNAPSHOT-all.jar
```

Add that jar to your desktop database tool's custom JDBC driver libraries. The driver class is:

```text
org.sshsqlite.jdbc.SshSqliteDriver
```

## Verify Remote SQLite

Install or verify a trusted `sqlite3` CLI on the SSH server:

```bash
ssh alice@db.example.org '/usr/bin/sqlite3 --version'
```

The SSH login user must not be able to replace `/usr/bin/sqlite3` or any parent directory. If you configure another `sqlite3.path`, verify that path and its parent directories are trusted.

## Configure Database Permissions

Grant the SSH account only the database filesystem permissions needed for the intended mode:

```text
/srv/app/app.db readable by the SSH account for readonly=true
/srv/app/app.db and sidecars readable/writable by the SSH account for readonly=false
/usr/bin/sqlite3 managed by the server package manager or operator
```

Do not put SQL or user-controlled database paths into ad hoc SSH shell commands. The driver safely passes the database path as the `sqlite3` argument and sends SQL only on stdin.

## Configure Known Hosts

Pin the SSH host key locally:

```bash
Host-key verification uses `~/.ssh/known_hosts` by default. Configure your desktop tool or SSH environment the same way you normally do for SSH host verification.
```

Review the scanned key fingerprint out of band before trusting it. Production connections must fail closed on unknown or changed host keys.

## Configure DBeaver

Create a new DBeaver driver with these settings:

```text
Driver Name: SSHSQLite
Driver Type: Generic
Class Name: org.sshsqlite.jdbc.SshSqliteDriver
URL Template: jdbc:sshsqlite://{host}:{port}/{database}
Default Port: 22
```

In the driver Libraries tab, add:

```text
dist/sshsqlite-driver-0.1.0-SNAPSHOT-all.jar
```

Create a connection with the full JDBC URL:

```text
jdbc:sshsqlite://alice@db.example.org:22/srv/app/app.db
```

Use the URL user (`alice@`) or set `ssh.user` as a driver property if your DBeaver workflow keeps the host and user fields separate.

## Connection Properties

Common properties:

```text
sqlite3.path=/usr/bin/sqlite3
readonly=false
```

Defaults:

```text
sqlite3.path=/usr/bin/sqlite3
readonly=false
ssh.knownHosts=~/.ssh/known_hosts
ssh.agent=false
```

If no password or private key is configured, the driver tries the first existing standard private key under `~/.ssh` in this order: `id_ed25519`, `id_ecdsa`, `id_rsa`, `id_dsa`, `identity`.

Use connection properties or DBeaver's credential fields for secrets. Do not put passwords, private key material, or SQL in SSH commands. The driver starts `sqlite3` with `-batch`, adds `-readonly` only when `readonly=true`, passes the database path as the SQLite database argument, and sends SQL on stdin.

Accepted desktop/JDBC aliases include:

```text
user, username, UID -> ssh.user
password, pass, PWD -> ssh.password
knownHosts, knownHostsFile -> ssh.knownHosts
privateKey, privateKeyFile, keyFile, identityFile, sshKey -> ssh.privateKey
privateKeyPassphrase, passphrase -> ssh.privateKeyPassphrase
sqlite3Path, sqlitePath -> sqlite3.path
database -> db.path
```

Explicit `ssh.*`, `sqlite3.path`, and `db.path` properties win over aliases when both are supplied.

## Setup Summary

DBeaver setup is intentionally simple and property-driven:

1. Add `dist/sshsqlite-driver-0.1.0-SNAPSHOT-all.jar` to the DBeaver driver libraries.
2. Use `jdbc:sshsqlite://alice@db.example.org:22/srv/app/app.db`.
3. Ensure `/usr/bin/sqlite3` exists on the remote SSH server, or set `sqlite3.path` to another trusted remote sqlite3 executable.
4. Use the default `readonly=false` for normal read/write use, or set `readonly=true` for inspection-only sessions.
5. Configure SSH auth through DBeaver fields or connection properties such as `ssh.privateKey`, `identityFile`, `ssh.password`, or `password`.
6. Keep backups for any database you might modify.

## Build Yourself

If you want to rebuild the dist jar yourself, run:

```bash
./build.sh
```

That writes:

```text
dist/sshsqlite-driver-0.1.0-SNAPSHOT-all.jar
dist/sshsqlite_SHA256SUMS
```

## Local Verification

```bash
./gradlew verify
./gradlew packageRelease
```
