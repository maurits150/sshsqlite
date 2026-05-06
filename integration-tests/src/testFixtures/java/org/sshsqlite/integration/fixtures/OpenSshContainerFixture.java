package org.sshsqlite.integration.fixtures;

/** Skeleton for tests that need OpenSSH, host keys, Unix permissions, and sqlite3 CLI exec. */
public final class OpenSshContainerFixture {
    public String requiredCapabilities() {
        return "host-keys,unix-permissions,sqlite3-cli-exec";
    }
}
