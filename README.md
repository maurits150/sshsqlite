# SSHSQLite

> # ☣️☣️☣️ WARNING: EXPERIMENTAL, UNTRUSTED, AND NOT PRODUCTION SAFE, YOU WILL LOOSE ALL YOUR DATA! ☣️☣️☣️
>
> TLDR; ACTUALLY JUST COMPLETELY VIBE CODED TRASH - Built by letting an agent slave away for a few hours.
>
> This project was built with literally no supervision and should be treated as unreliable prototype code.
> Do not point it at important databases unless you have backups, understand the failure modes,
> and are willing to debug/repair issues yourself. Expect sharp edges, incomplete behavior,
> and surprising breakage. Just don't use it for anything except quick development work, that is what I needed it for.

SSHSQLite is a JDBC driver for opening a remote SQLite database through SSH. The Java driver connects to the server over SSH, verifies the host key, starts the remote `sqlite3` CLI, and sends SQL over stdin while parsing stable CLI output.

## Grab The Jar

Most people should just use the prebuilt jar in this repo:

```text
dist/sshsqlite-driver-0.1.0-SNAPSHOT-all.jar
```

Add that jar to DBeaver's custom JDBC driver libraries.

The driver jar is compiled for Java 11 (`--release 11`) so it works with DBeaver runtimes that still use Java 11.

## Server Requirements

The remote server must have the `sqlite3` CLI installed. SSHSQLite does not upload SQLite or run an embedded server-side helper.

Install or verify `sqlite3` on the SSH server:

```bash
ssh alice@db.example.org '/usr/bin/sqlite3 --version'
```

If that command fails, install SQLite on the server first, for example with your OS package manager.

The SSH account needs filesystem permissions for the target database:

```text
/srv/app/app.db readable by the SSH account, and writable when readonly=false is used
/usr/bin/sqlite3 managed by the server operator/package manager
```

Read/write is the default. The driver starts `sqlite3` without `-readonly` and allows write SQL unless `readonly=true` is set. For read-only sessions, set `readonly=true`; the driver passes `-readonly` and rejects mutations.

Host-key verification uses the normal SSH known-hosts file by default: `~/.ssh/known_hosts`. You can override it with `ssh.knownHosts` or common desktop-tool known-hosts fields.

## JDBC URL

If the remote database file is:

```text
Host: db.example.org
User: alice
Path: /srv/app/app.db
```

becomes this JDBC URL:

```text
jdbc:sshsqlite://alice@db.example.org:22/srv/app/app.db
```

## DBeaver Setup

Create a new driver with these settings:

```text
Driver Name: SSHSQLite
Driver Type: Generic
Class Name: org.sshsqlite.jdbc.SshSqliteDriver
URL Template: jdbc:sshsqlite://{host}:{port}/{database}
Default Port: 22
```

In the driver **Libraries** tab, add:

```text
dist/sshsqlite-driver-0.1.0-SNAPSHOT-all.jar
```

Then create a connection using the full JDBC URL. Include the SSH username in the URL authority as shown, or set `ssh.user` as a driver property if your DBeaver workflow keeps the host field separate:

```text
jdbc:sshsqlite://alice@db.example.org:22/srv/app/app.db
```

Optional driver/user properties:

```text
sqlite3.path=/usr/bin/sqlite3
```

`ssh.knownHosts` defaults to `~/.ssh/known_hosts`. If no password or `ssh.privateKey` is configured, the driver tries standard private keys from `~/.ssh` in this order: `id_ed25519`, `id_ecdsa`, `id_rsa`, `id_dsa`, `identity`.

Set `readonly=true` only when you want a read-only session. Production write access should still have a tested backup/restore workflow, but normal use does not require a custom helper binary or helper integrity properties.

If you use password auth instead of a key, set the normal DBeaver password field or set `ssh.password` as a driver property. Do not put passwords or private key material in the JDBC URL.

## Build Yourself

If you want to rebuild the jar yourself, run:

```bash
./build.sh
```

That writes:

```text
dist/sshsqlite-driver-0.1.0-SNAPSHOT-all.jar
dist/sshsqlite_SHA256SUMS
```

Full local verification:

```bash
./gradlew verify packageRelease
```

Optional pre-commit hook:

```bash
ln -sf ../../.githooks/pre-commit .git/hooks/pre-commit
```

Inspect remote sqlite3 diagnostics:

```bash
ssh alice@db.example.org '/usr/bin/sqlite3 --version'
```

## Notes

- The driver requires Java 11 or newer.
- The remote sqlite3 CLI path defaults to `/usr/bin/sqlite3` and uses CSV output mode for compatibility with SQLite 3.27.2 and newer.
- The remote SQLite version controls SQL feature support. Older servers such as SQLite 3.27.x do not support newer syntax like `ALTER TABLE ... DROP COLUMN`, `RETURNING`, generated columns, `STRICT` tables, `RIGHT/FULL JOIN`, or `.mode json`.
- Normal use does not require installing a custom helper binary.
- Production release verification requires signed artifacts and real DBeaver evidence; development checks can use the documented Gradle bypass properties.

## License

MIT. See `LICENSE`.
