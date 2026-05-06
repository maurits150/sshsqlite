package org.sshsqlite.integration.fixtures;

/** Skeleton for fast protocol tests that can run a helper as a local process without SSH. */
public final class LocalProcessTransportFixture {
    public String transportName() {
        return "local-process";
    }
}
